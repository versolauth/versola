package versola.central.configuration.topology

import versola.central.CentralConfig
import versola.util.{CacheSource, ReloadingCache}
import zio.{Scope, Task, UIO, ZIO, ZLayer}

/** The cells as central keeps them in memory (#1050): every call an `auth` makes is checked against the keys of the
  * registered cells, so the keys are read from here and not from the table on each call. Reads only; [[TopologyService]]
  * writes, through the repository, and brings the change here at once ([[refreshNow]]). Other replicas of central see it at
  * the next refresh, `configuration-cache-refresh-interval` -- the freshness an edge's key has in [[versola.central.configuration.edges.EdgeService]].
  * Kept apart from the service so that [[versola.central.authorizeInternal]] can read the keys without the service
  * depending on what registers users in turn.
  */
trait CellRegistry:
  def all: UIO[Vector[CellRecord]]

  /** Reloads the cache now rather than at the next refresh: for a write whose effect the next call must see. */
  def refreshNow: Task[Unit]

object CellRegistry:
  val live: ZLayer[TopologyRepository & Scope & CentralConfig, Throwable, CellRegistry] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[TopologyRepository]
        config <- ZIO.service[CentralConfig]
        source: CacheSource[Vector[CellRecord]] = new CacheSource[Vector[CellRecord]]:
          override def getAll: Task[Vector[CellRecord]] = repository.getCells
        cache <- ReloadingCache.make[Vector[CellRecord]](config.configurationCacheRefreshInterval).provideSome[Scope](ZLayer.succeed(source))
      yield Impl(cache, repository)

  class Impl(cache: ReloadingCache[Vector[CellRecord]], repository: TopologyRepository) extends CellRegistry:
    override def all: UIO[Vector[CellRecord]] = cache.get

    override def refreshNow: Task[Unit] = repository.getCells.flatMap(cache.set)
