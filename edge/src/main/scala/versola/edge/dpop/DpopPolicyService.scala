package versola.edge.dpop

import versola.edge.{DpopAlgorithmsSyncClient, DpopPolicy, DpopPolicySyncClient, EdgeConfig}
import versola.util.{Dpop, ReloadingCache}
import zio.{Scope, Task, UIO, ZIO, ZLayer}

/** What this edge holds a proxied call's DPoP proof to, as central currently says it -- the two
  * halves of RFC 9449 proof policy that are decisions rather than deployment facts.
  *
  * Both are cached on `configurationCacheRefreshInterval` like every other thing edge syncs, so
  * a console change reaches a running edge on its own; neither is read per request from central.
  */
trait DpopPolicyService:
  /** §5.1: the algorithms an incoming proof's `alg` may use. */
  def allowedAlgorithms: UIO[Set[Dpop.Algorithm]]

  /** §9: whether every proof must carry a nonce this edge issued. */
  def requireNonce: UIO[Boolean]

  /** Reloads both caches from central now, instead of waiting for the refresh interval. Backs
    * the non-prod `/service/configuration/sync` endpoint; nothing in request handling calls
    * this. */
  def refreshNow: Task[Unit]

object DpopPolicyService:
  def live: ZLayer[
    DpopAlgorithmsSyncClient & DpopPolicySyncClient & Scope & EdgeConfig,
    Throwable,
    DpopPolicyService,
  ] =
    (
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[Set[Dpop.Algorithm]](config.configurationCacheRefreshInterval),
        )
      ) ++
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[DpopPolicy](config.configurationCacheRefreshInterval),
        )
      ) ++
      ZLayer.service[DpopAlgorithmsSyncClient] ++
      ZLayer.service[DpopPolicySyncClient]
    ) >>> ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      algorithmsCache: ReloadingCache[Set[Dpop.Algorithm]],
      policyCache: ReloadingCache[DpopPolicy],
      algorithmsSource: DpopAlgorithmsSyncClient,
      policySource: DpopPolicySyncClient,
  ) extends DpopPolicyService:

    override def allowedAlgorithms: UIO[Set[Dpop.Algorithm]] = algorithmsCache.get

    override def requireNonce: UIO[Boolean] = policyCache.get.map(_.requireNonce)

    override def refreshNow: Task[Unit] =
      (
        algorithmsSource.getAll.flatMap(algorithmsCache.set) <&>
          policySource.getAll.flatMap(policyCache.set)
      ).unit
