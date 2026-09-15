package versola.oauth.dpop

import versola.oauth.client.model.TenantId
import versola.oauth.client.{CentralSyncTokenService, EdgeRegistrySyncClient}
import versola.util.{CacheSource, CoreConfig, EdgeAssertion, ReloadingCache}
import zio.http.Client
import zio.metrics.Metric
import zio.{Scope, UIO, ZIO, ZLayer}

/** Authenticates the edge behind a call that arrives with a bound access token but no DPoP
  * proof (see [[versola.util.EdgeAssertion]] for why such a call is legitimate and why nothing
  * else can vouch for it).
  *
  * Fails closed and quietly: every rejection returns `None`, which leaves the caller on the
  * path it would have taken had no assertion been sent at all -- the §7.2 refusal. There is no
  * error to report, because an unauthenticated caller learns nothing from being told which of
  * the checks it failed, and a genuine edge fails none of them.
  */
trait EdgeAssertionService:
  /** The id of the edge that signed `assertion` for `accessToken`, or `None` if nothing did.
    *
    * `tenantId` is the tenant that owns the token's client: a correctly signed, unexpired,
    * correctly `ath`-bound assertion still says nothing on its own about which tenants that
    * edge may vouch for, so an edge not assigned to serve this tenant is refused exactly as
    * if it had signed nothing at all.
    */
  def verify(assertion: String, accessToken: String, tenantId: TenantId): UIO[Option[String]]

object EdgeAssertionService:
  /** How often a bound token was honoured under `Bearer` because an edge vouched for it.
    *
    * This is the one path on which auth accepts a bound token without seeing a proof, so it is
    * the one worth being able to count. A rate that does not track `fetchUserInfo` traffic --
    * or any volume at all from an environment with no edges -- is the signal that the key of
    * some edge is being used somewhere it should not be.
    */
  private val exemptions = Metric.counter("dpop_edge_assertion_exemptions_total")

  /** A presented assertion that did not check out. Ordinary during an edge key rotation auth
    * has not yet synced; sustained otherwise, it is either a misconfigured edge, a tenant
    * reassignment auth has not yet synced, or something trying to strip DPoP off a stolen
    * token (including by replaying an assertion it captured).
    */
  private val rejections = Metric.counter("dpop_edge_assertion_rejections_total")

  val live: ZLayer[Scope & CoreConfig & Client & DpopProofRepository, Throwable, EdgeAssertionService] =
    CentralSyncTokenService.live >+> EdgeRegistrySyncClient.live >+> cacheLayer >>>
      ZLayer.fromFunction(Impl(_, _))

  private val cacheLayer: ZLayer[
    Scope & CoreConfig & CacheSource[Map[String, EdgeRegistrySyncClient.EdgeRegistration]],
    Throwable,
    ReloadingCache[Map[String, EdgeRegistrySyncClient.EdgeRegistration]],
  ] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[CoreConfig](config =>
        ReloadingCache.make[Map[String, EdgeRegistrySyncClient.EdgeRegistration]](
          config.configurationCacheRefreshInterval,
        ),
      )

  /** Scopes an assertion's `jti` to the edge that signed it, the same reason a DPoP proof's
    * `jti` is scoped to its `jkt`: an accidental collision between two unrelated edges must not
    * reject a legitimate assertion as a replay.
    */
  private def replayScope(edgeId: String): String = s"edge-assertion:$edgeId"

  class Impl(
      edgeRegistrations: ReloadingCache[Map[String, EdgeRegistrySyncClient.EdgeRegistration]],
      proofRepository: DpopProofRepository,
  ) extends EdgeAssertionService:
    override def verify(assertion: String, accessToken: String, tenantId: TenantId): UIO[Option[String]] =
      (for
        edgeId <- EdgeAssertion.edgeIdOf(assertion)
        registration <- edgeRegistrations.get.map(_.get(edgeId)).someOrFail(EdgeAssertion.Error.UnknownEdge)
        _ <- ZIO.fail(EdgeAssertion.Error.WrongTenant).unless(registration.tenantIds.contains(tenantId))
        verified <- EdgeAssertion.verify(assertion, registration.keys, accessToken)
        // Not `verified.issuedAt` itself: the repository's ring evicts on the same symmetric
        // `[iat-L, iat+L]` shape `DpopService` uses for an ordinary proof's `iatLeeway`, while
        // an assertion's own window is the one-sided `[issuedAt, issuedAt+Ttl]`. Recording it
        // centred, with `L = Ttl/2` (see `PostgresDpopProofRepository.EdgeAssertionEvictionLeeway`),
        // makes the two windows the exact same interval, so the ring's already-proven eviction
        // guarantee for the symmetric case covers this one without a separate argument.
        centered = verified.issuedAt.plus(EdgeAssertion.Ttl.dividedBy(2))
        fresh <- proofRepository.recordIfAbsent(replayScope(edgeId), verified.jti, centered)
        _ <- ZIO.fail(EdgeAssertion.Error.Replayed).unless(fresh)
      yield edgeId).foldZIO(
        _ => rejections.increment.as(None),
        edgeId => exemptions.increment.as(Some(edgeId)),
      )
