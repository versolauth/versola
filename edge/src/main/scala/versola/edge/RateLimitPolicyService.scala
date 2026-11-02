package versola.edge

import versola.util.ReloadingCache
import versola.util.http.RateLimitPolicy
import zio.{Scope, Task, UIO, ZIO, ZLayer}

/** The rate limit policies central currently holds for this edge, cached on `configurationCacheRefreshInterval` like everything
  * else the edge syncs, and served from the configuration snapshot while central is unreachable. [[RateLimitPolicySource]]
  * feeds them to the running limiter; nothing in request handling reads them.
  */
trait RateLimitPolicyService:
  /** The policies now, empty when none are set. */
  def current: UIO[List[RateLimitPolicy]]

  /** How many edge pods share the traffic, so each enforces its share of a limit. */
  def replicas: UIO[Int]

  /** Reloads from central now, instead of waiting for the refresh interval (the non-prod `/service/configuration/sync`). */
  def refreshNow: Task[Unit]

object RateLimitPolicyService:
  def live: ZLayer[RateLimitPolicySyncClient & Scope & EdgeConfig, Throwable, RateLimitPolicyService] =
    (
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[EdgeRateLimitPolicies](config.configurationCacheRefreshInterval, fromSnapshot = true),
        )
      ) ++
        ZLayer.service[RateLimitPolicySyncClient]
    ) >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[EdgeRateLimitPolicies],
      source: RateLimitPolicySyncClient,
  ) extends RateLimitPolicyService:
    override def current: UIO[List[RateLimitPolicy]] = cache.get.map(_.policies)

    override def replicas: UIO[Int] = cache.get.map(_.replicas)

    override def refreshNow: Task[Unit] = source.getAll.flatMap(cache.set)
