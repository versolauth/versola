package versola.edge

import versola.util.ReloadingCache
import versola.util.http.OverloadPolicy
import zio.{Scope, Task, UIO, ZIO, ZLayer}

/** The overload policy central currently holds for this edge, cached on `configurationCacheRefreshInterval` like everything
  * else the edge syncs, and served from the configuration snapshot while central is unreachable. Applied to the running
  * admission and outbound guard by `VersolaApp` (see `overloadPolicy`); nothing in request handling reads it.
  */
trait OverloadPolicyService:
  /** The policy now, `None` when none is set. */
  def current: UIO[Option[OverloadPolicy]]

  /** Reloads from central now, instead of waiting for the refresh interval (the non-prod `/service/configuration/sync`). */
  def refreshNow: Task[Unit]

object OverloadPolicyService:
  def live: ZLayer[OverloadPolicySyncClient & Scope & EdgeConfig, Throwable, OverloadPolicyService] =
    (
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[EdgeOverloadPolicy](config.configurationCacheRefreshInterval, fromSnapshot = true),
        )
      ) ++
        ZLayer.service[OverloadPolicySyncClient]
    ) >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[EdgeOverloadPolicy],
      source: OverloadPolicySyncClient,
  ) extends OverloadPolicyService:
    override def current: UIO[Option[OverloadPolicy]] = cache.get.map(_.policy)

    override def refreshNow: Task[Unit] = source.getAll.flatMap(cache.set)
