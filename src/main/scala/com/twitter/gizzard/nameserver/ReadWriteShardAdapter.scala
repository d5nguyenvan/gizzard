package com.twitter.gizzard.nameserver

import shards.{ReadWriteShard, ShardId, ShardInfo, Busy}

class ReadWriteShardAdapter(shard: ReadWriteShard[Shard]) extends shards.ReadWriteShardAdapter(shard) with Shard {
  def getBusyShards()                                             = shard.readOperation(_.getBusyShards())
  def getForwarding(tableId: Int, baseId: Long)                   = shard.readOperation(_.getForwarding(tableId, baseId))
  def getForwardingForShard(id: ShardId)                          = shard.readOperation(_.getForwardingForShard(id))
  def getForwardings()                                            = shard.readOperation(_.getForwardings())
  def getForwardingsForTableIds(tableIds: Seq[Int]): Seq[Forwarding] = shard.readOperation(_.getForwardingsForTableIds(tableIds))
  def getShard(id: ShardId)                                       = shard.readOperation(_.getShard(id))
  def listUpwardLinks(id: ShardId)                                = shard.readOperation(_.listUpwardLinks(id))
  def listDownwardLinks(id: ShardId)                              = shard.readOperation(_.listDownwardLinks(id))
  def listLinks()                                                 = shard.readOperation(_.listLinks())
  def listShards()                                                = shard.readOperation(_.listShards())
  def shardsForHostname(hostname: String)                         = shard.readOperation(_.shardsForHostname(hostname))
  def listHostnames()                                             = shard.readOperation(_.listHostnames())
  def listTables()                                                = shard.readOperation(_.listTables())

  def currentState()                                              = shard.readOperation(_.currentState())

  def createShard[S <: shards.Shard](shardInfo: ShardInfo, repository: ShardRepository[S]) = shard.writeOperation(_.createShard(shardInfo, repository))
  def deleteShard(id: ShardId)                                    = shard.writeOperation(_.deleteShard(id))
  def markShardBusy(id: ShardId, busy: Busy.Value)                = shard.writeOperation(_.markShardBusy(id, busy))
  def addLink(upId: ShardId, downId: ShardId, weight: Int)        = shard.writeOperation(_.addLink(upId, downId, weight))
  def removeLink(upId: ShardId, downId: ShardId)                  = shard.writeOperation(_.removeLink(upId, downId))
  def replaceForwarding(oldId: ShardId, newId: ShardId)           = shard.writeOperation(_.replaceForwarding(oldId, newId))
  def setForwarding(forwarding: Forwarding)                       = shard.writeOperation(_.setForwarding(forwarding))
  def removeForwarding(forwarding: Forwarding)                    = shard.writeOperation(_.removeForwarding(forwarding))
  def reload()                                                    = shard.writeOperation(_.reload())
  def rebuildSchema()                                             = shard.writeOperation(_.rebuildSchema())


  // Remote Host Cluster Management

  def addRemoteHost(h: Host)                                      = shard.writeOperation(_.addRemoteHost(h))
  def removeRemoteHost(h: String, p: Int)                         = shard.writeOperation(_.removeRemoteHost(h, p))
  def setRemoteHostStatus(h: String, p: Int, s: HostStatus.Value) = shard.writeOperation(_.setRemoteHostStatus(h, p, s))
  def setRemoteClusterStatus(c: String, s: HostStatus.Value)      = shard.writeOperation(_.setRemoteClusterStatus(c, s))

  def getRemoteHost(h: String, p: Int)                            = shard.readOperation(_.getRemoteHost(h, p))
  def listRemoteClusters()                                        = shard.readOperation(_.listRemoteClusters())
  def listRemoteHosts()                                           = shard.readOperation(_.listRemoteHosts())
  def listRemoteHostsInCluster(c: String)                         = shard.readOperation(_.listRemoteHostsInCluster(c))
}
