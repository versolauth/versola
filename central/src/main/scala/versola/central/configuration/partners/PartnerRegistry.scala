package versola.central.configuration.partners

import versola.central.CentralConfig
import versola.central.configuration.edges.EdgeGroupId
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.util.ReloadingCache
import zio.{Scope, Task, UIO, ZIO, ZLayer}

/** The partners as central keeps them in memory, like clients: the status of a partner is read on every call of one of its
  * edges, and the sync to auth and to the first-party edges filters by it. Reads only; `PartnerService` writes, through the
  * repository, and the `partner_change` notification brings the change here. Kept apart from the service so that what
  * registers clients and resources can read a partner without the service depending on them in turn. */
trait PartnerRegistry:
  def getAll: UIO[Vector[PartnerRecord]]

  def find(id: PartnerId): UIO[Option[PartnerRecord]]

  /** The partner whose edge group this is: how a partner's edge is resolved, `edge -> group -> partner`. */
  def findByGroup(groupId: EdgeGroupId): UIO[Option[PartnerRecord]]

  /** The stored partner, through the repository when the cache has not caught up with a write of a moment ago. */
  def findFresh(id: PartnerId): Task[Option[PartnerRecord]]

  def sync(event: SyncEvent.PartnersUpdated): Task[Unit]

  /** Reloads the whole cache now rather than at the next refresh or notification: for a write whose effect must be seen by
    * the next call (a suspension). */
  def refreshNow: Task[Unit]

object PartnerRegistry:
  val live: ZLayer[PartnerRepository & Scope & CentralConfig, Throwable, PartnerRegistry] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config => ReloadingCache.make[Vector[PartnerRecord]](config.configurationCacheRefreshInterval))
    ) >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(cache: ReloadingCache[Vector[PartnerRecord]], repository: PartnerRepository) extends PartnerRegistry:
    override def getAll: UIO[Vector[PartnerRecord]] = cache.get.map(_.sortBy(_.id))

    override def find(id: PartnerId): UIO[Option[PartnerRecord]] = cache.get.map(_.find(_.id == id))

    override def findByGroup(groupId: EdgeGroupId): UIO[Option[PartnerRecord]] = cache.get.map(_.find(_.groupId == groupId))

    override def findFresh(id: PartnerId): Task[Option[PartnerRecord]] =
      cache.get.map(_.find(_.id == id)).flatMap:
        case some @ Some(_) => ZIO.succeed(some)
        case None => repository.find(id)

    override def sync(event: SyncEvent.PartnersUpdated): Task[Unit] =
      SyncOps.syncCache(event)(cache, repository.find(event.id))

    override def refreshNow: Task[Unit] = repository.getAll.flatMap(cache.set)
