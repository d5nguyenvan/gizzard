package com.twitter.gizzard.scheduler

import java.util.Random
import com.twitter.ostrich.{BackgroundProcess, Stats}
import com.twitter.util.Duration
import com.twitter.util.TimeConversions._
import net.lag.configgy.ConfigMap
import net.lag.kestrel.PersistentQueue
import net.lag.logging.Logger
import java.util.concurrent.atomic.AtomicInteger
import shards.{ShardBlackHoleException, ShardRejectedOperationException}

object JobScheduler {
  /**
   * Configure a JobScheduler from a queue ConfigMap and a scheduler-specific ConfigMap, creating
   * a new ErrorHandlingJobParser and linking the job & error queues together through it.
   */
  def apply[J <: Job](name: String, queueConfig: ConfigMap, codec: Codec[J],
                      badJobQueue: Option[JobConsumer[J]]) = {
    val path = queueConfig.getString("path", "/var/spool/kestrel")
    val schedulerConfig = queueConfig.configMap(name)

    val threadCount = schedulerConfig("threads").toInt
    val strobeInterval = schedulerConfig("strobe_interval").toInt.milliseconds
    val errorLimit = schedulerConfig("error_limit").toInt
    val flushLimit = schedulerConfig("flush_limit").toInt
    val errorDelay = schedulerConfig("error_delay").toInt.seconds
    val sizeLimit = schedulerConfig.getInt("size_limit", 0)
    val jitterRate = schedulerConfig("jitter_rate").toFloat

    val jobQueueName = schedulerConfig("job_queue")
    val errorQueueName = schedulerConfig("error_queue")

    val (jobQueue, errorQueue) = schedulerConfig.getString("type", "kestrel") match {
      case "kestrel" => {
        val persistentJobQueue = new PersistentQueue(path, jobQueueName, queueConfig)
        val jobQueue = new KestrelJobQueue[J](jobQueueName, persistentJobQueue, codec)

        val persistentErrorQueue = new PersistentQueue(path, errorQueueName, queueConfig)
        val errorQueue = new KestrelJobQueue[J](errorQueueName, persistentErrorQueue, codec)

        (jobQueue, errorQueue)
      }

      case "memory" => {
        val jobQueue = new MemoryJobQueue[J](jobQueueName, sizeLimit)
        val errorQueue = new MemoryJobQueue[J](errorQueueName, sizeLimit)

        (jobQueue, errorQueue)
      }

      case x => throw new Exception("Unknown queue type " + x)
    }

    errorQueue.drainTo(jobQueue, errorDelay)

    new JobScheduler[J](name, threadCount, strobeInterval, errorLimit, flushLimit,
                        jitterRate, jobQueue, errorQueue, badJobQueue)
  }
}

/**
 * A cluster of worker threads which poll a JobQueue for work and execute jobs.
 *
 * If a job throws an exception, its error count is incremented. If the error count exceeds
 * 'errorLimit', the job is written into 'badJobQueue', which is usually some sort of log file.
 * Otherwise, the job is written into 'errorQueue'.
 *
 * At regular intervals specified by 'retryInterval', 'errorQueue' is drained back into 'queue'
 * to give erroring jobs another chance to run.
 *
 * Jobs are added to the scheduler with 'put', and the thread pool & queues can be controlled
 * with the normal Process methods ('start', 'shutdown', and so on).
 */
class JobScheduler[J <: Job](val name: String,
                             val threadCount: Int,
                             val strobeInterval: Duration,
                             val errorLimit: Int,
                             val flushLimit: Int,
                             val jitterRate: Float,
                             val queue: JobQueue[J],
                             val errorQueue: JobQueue[J],
                             val badJobQueue: Option[JobConsumer[J]])
      extends Process with JobConsumer[J] {

  private val log = Logger.get(getClass.getName)
  var workerThreads: Collection[BackgroundProcess] = Nil
  @volatile var running = false
  private var _activeThreads = new AtomicInteger

  def activeThreads = _activeThreads.get()

  val retryTask = new BackgroundProcess("Retry process for " + name + " errors") {
    def runLoop() {
      val jitter = Math.round(strobeInterval.inMillis * jitterRate * new Random().nextGaussian())
      Thread.sleep(strobeInterval.inMillis + jitter)
      try {
        checkExpiredJobs()
      } catch {
        case e: Throwable =>
          log.error(e, "Error replaying %s errors!", name)
      }
    }
  }

  def retryErrors() {
    var bound = errorQueue.size
    while (bound > 0) {
      errorQueue.get() match {
        case None    => bound = 0
        case Some(t) => {
          queue.put(t.job)
          t.ack()
          bound -= 1
        }
      }
    }
  }

  def checkExpiredJobs() {
    errorQueue.checkExpiration(flushLimit)
  }

  def start() = {
    if (!running) {
      queue.start()
      errorQueue.start()
      running = true
      log.info("Starting JobScheduler: %s", queue)
      workerThreads = (0 until threadCount).map { makeWorker(_) }.toList
      workerThreads.foreach { _.start() }
      retryTask.start()
    }
  }

  def pause() {
    log.info("Pausing work in JobScheduler: %s", queue)
    queue.pause()
    errorQueue.pause()
    shutdownWorkerThreads()
  }

  def resume() = {
    log.info("Resuming work in JobScheduler: %s", queue)
    queue.resume()
    errorQueue.resume()
    workerThreads = (0 until threadCount).map { makeWorker(_) }.toList
    workerThreads.foreach { _.start() }
  }

  def shutdown() {
    if(running) {
      log.info("Shutting down JobScheduler: %s", queue)
      queue.shutdown()
      errorQueue.shutdown()
      shutdownWorkerThreads()
      retryTask.shutdown()
      running = false
    }
  }

  private def shutdownWorkerThreads() {
    workerThreads.foreach { _.shutdown() }
    workerThreads = Nil
  }

  def isShutdown = queue.isShutdown

  def put(job: J) {
    queue.put(job)
  }

  def size = queue.size

  private def makeWorker(n: Int) = {
    new BackgroundProcess("JobEvaluatorThread:" + name + ":" + n.toString) {
      def runLoop() {
        processWork()
      }
    }
  }

  // hook to let unit tests stub out threads.
  protected def processWork() {
    process()
  }

  def process() {
    queue.get.foreach { ticket =>
      _activeThreads.incrementAndGet()
      try {
        val job = ticket.job
        try {
            job()
            Stats.incr("job-success-count")
        } catch {
          case e: ShardBlackHoleException =>
            Stats.incr("job-blackholed-count")
          case e: ShardRejectedOperationException =>
            Stats.incr("job-darkmoded-count")
            errorQueue.put(job)
          case e =>
            Stats.incr("job-error-count")
            log.error(e, "Error in Job: %s - %s", job, e)
            job.errorCount += 1
            job.errorMessage = e.toString
            if (job.errorCount > errorLimit) {
              badJobQueue.foreach { _.put(job) }
            } else {
              errorQueue.put(job)
            }
        }
       ticket.ack()
      } finally {
        _activeThreads.decrementAndGet()
      }
    }
  }
}
