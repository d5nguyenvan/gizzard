package com.twitter.gizzard.scheduler

import scala.collection.Map
import scala.collection.mutable
import net.lag.configgy.ConfigMap


object PrioritizingJobScheduler {
  def apply[J <: Job](config: ConfigMap, codec: Codec[J], queueNames: Map[Int, String],
                      badJobQueue: Option[JobConsumer[J]]) = {
    val schedulerMap = new mutable.HashMap[Int, JobScheduler[J]]
    queueNames.foreach { case (priority, queueName) =>
      schedulerMap(priority) = JobScheduler(queueName, config, codec, badJobQueue)
    }
    new PrioritizingJobScheduler[J](schedulerMap)
  }
}

/**
 * A map of JobSchedulers by priority. It can be treated as a single scheduler, and all process
 * operations work on the cluster of schedulers as a whole.
 */
class PrioritizingJobScheduler[J <: Job](val _schedulers: Map[Int, JobScheduler[J]]) extends Process {
  val schedulers = mutable.Map.empty[Int, JobScheduler[J]] ++ _schedulers

  def put(priority: Int, job: J) {
    apply(priority).put(job)
  }

  def apply(priority: Int): JobScheduler[J] = {
    schedulers.get(priority).getOrElse {
      throw new Exception("No scheduler for priority " + priority)
    }
  }

  def update(priority: Int, scheduler: JobScheduler[J]) {
    schedulers(priority) = scheduler
  }

  def start() = schedulers.values.foreach { _.start() }
  def shutdown() = schedulers.values.foreach { _.shutdown() }
  def isShutdown = schedulers.values.forall { _.isShutdown }
  def pause() = schedulers.values.foreach { _.pause() }
  def resume() = schedulers.values.foreach { _.resume() }
  def retryErrors() = schedulers.values.foreach { _.retryErrors() }

  def size = schedulers.values.foldLeft(0) { _ + _.size }
  def activeThreads = schedulers.values.foldLeft(0) { _ + _.activeThreads }
}
