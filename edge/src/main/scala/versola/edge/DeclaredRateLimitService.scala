package versola.edge

import versola.edge.model.ClientId
import versola.util.ReloadingCache
import versola.util.http.{DeclaredLimit, DeclaredOwner, DeclaredRateLimit}
import zio.{Scope, Task, UIO, ZIO, ZLayer}

/** The rate limits declared where an API or a client is declared (levels 2 and 3 of issue #744 section 4): on a resource endpoint,
  * and on an OAuth client. Derived from what the edge already syncs, so a change in central reaches the limiter by the existing
  * refresh, and nothing here is read while handling a request: [[RateLimitPolicyApplier]] polls [[current]] and hands the
  * result to the limiter, which keeps the buckets of a declaration whose shape did not change.
  */
trait DeclaredRateLimitService:
  /** Every limit declared now, endpoints first. A limit in mode `off` is left to the limiter, which does not install it. */
  def current: UIO[List[DeclaredLimit]]

  /** Reloads the client limits from central now, instead of waiting for the refresh interval (the non-prod
    * `/service/configuration/sync`). The endpoint limits come with [[ResourceService.refreshNow]]. */
  def refreshNow: Task[Unit]

object DeclaredRateLimitService:
  val live: ZLayer[ClientRateLimitsSyncClient & ResourceService & Scope & EdgeConfig, Throwable, DeclaredRateLimitService] =
    (
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[Map[ClientId, DeclaredRateLimit]](config.configurationCacheRefreshInterval, fromSnapshot = true),
        )
      ) ++
        ZLayer.service[ClientRateLimitsSyncClient] ++
        ZLayer.service[ResourceService]
    ) >>> ZLayer.fromFunction(Impl(_, _, _))

  /** None declared (tests, tools). */
  val none: zio.ULayer[DeclaredRateLimitService] =
    ZLayer.succeed(new DeclaredRateLimitService:
      override val current: UIO[List[DeclaredLimit]] = ZIO.succeed(Nil)
      override val refreshNow: Task[Unit] = ZIO.unit)

  class Impl(
      clients: ReloadingCache[Map[ClientId, DeclaredRateLimit]],
      clientSource: ClientRateLimitsSyncClient,
      resources: ResourceService,
  ) extends DeclaredRateLimitService:

    override def current: UIO[List[DeclaredLimit]] =
      for
        resourceList <- resources.all
        clientMap <- clients.get
      yield
        val endpoints =
          for
            resource <- resourceList.toList.sortBy(_.resourceId.toString)
            endpoint <- resource.endpoints.toList
            limit <- endpoint.rateLimit
          yield DeclaredLimit(owner = DeclaredOwner.Endpoint, selector = endpoint.template(resource.resourceId), limit = limit)
        val byClient = clientMap.toList.sortBy(_._1.toString).map: (id, limit) =>
          DeclaredLimit(owner = DeclaredOwner.Client, selector = id.toString, limit = limit)
        endpoints ++ byClient

    override def refreshNow: Task[Unit] = clientSource.getAll.flatMap(clients.set)
