package versola.edge

import versola.util.ReloadingCache
import versola.util.http.AccessLogPolicy
import zio.{Scope, Task, UIO, ZIO, ZLayer}

/** The access-log policy central currently holds for this edge, cached on `configurationCacheRefreshInterval` like everything
  * else the edge syncs, and served from the configuration snapshot while central is unreachable. Applied to the running
  * access-log settings by `VersolaApp` (see `accessLogPolicy`); nothing in request handling reads it.
  */
trait AccessLogPolicyService:
  /** The policy now, `None` when none is set. */
  def current: UIO[Option[AccessLogPolicy]]

  /** Reloads from central now, instead of waiting for the refresh interval (the non-prod `/service/configuration/sync`). */
  def refreshNow: Task[Unit]

object AccessLogPolicyService:
  def live: ZLayer[AccessLogPolicySyncClient & Scope & EdgeConfig, Throwable, AccessLogPolicyService] =
    (
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[EdgeAccessLogPolicy](config.configurationCacheRefreshInterval, fromSnapshot = true),
        )
      ) ++
        ZLayer.service[AccessLogPolicySyncClient]
    ) >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[EdgeAccessLogPolicy],
      source: AccessLogPolicySyncClient,
  ) extends AccessLogPolicyService:
    override def current: UIO[Option[AccessLogPolicy]] = cache.get.map(_.policy)

    override def refreshNow: Task[Unit] = source.getAll.flatMap(cache.set)
