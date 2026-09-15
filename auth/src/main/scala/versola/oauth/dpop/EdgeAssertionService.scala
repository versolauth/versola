package versola.oauth.dpop

import versola.oauth.client.{CentralSyncTokenService, EdgeRegistrySyncClient}
import versola.util.{CacheSource, CoreConfig, EdgeAssertion, JWT, ReloadingCache}
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
  /** The id of the edge that signed `assertion` for `accessToken`, or `None` if nothing did. */
  def verify(assertion: String, accessToken: String): UIO[Option[String]]

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
    * has not yet synced; sustained otherwise, it is either a misconfigured edge or something
    * trying to strip DPoP off a stolen token.
    */
  private val rejections = Metric.counter("dpop_edge_assertion_rejections_total")

  val live: ZLayer[Scope & CoreConfig & Client, Throwable, EdgeAssertionService] =
    CentralSyncTokenService.live >+> EdgeRegistrySyncClient.live >+> cacheLayer >>>
      ZLayer.fromFunction(Impl(_))

  private val cacheLayer: ZLayer[
    Scope & CoreConfig & CacheSource[Map[String, JWT.PublicKeys]],
    Throwable,
    ReloadingCache[Map[String, JWT.PublicKeys]],
  ] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[CoreConfig](config =>
        ReloadingCache.make[Map[String, JWT.PublicKeys]](config.configurationCacheRefreshInterval),
      )

  class Impl(
      edgeKeys: ReloadingCache[Map[String, JWT.PublicKeys]],
  ) extends EdgeAssertionService:
    override def verify(assertion: String, accessToken: String): UIO[Option[String]] =
      (for
        edgeId <- EdgeAssertion.edgeIdOf(assertion)
        keys <- edgeKeys.get.map(_.get(edgeId)).someOrFail(EdgeAssertion.Error.UnknownEdge)
        _ <- EdgeAssertion.verify(assertion, keys, accessToken)
      yield edgeId).foldZIO(
        _ => rejections.increment.as(None),
        edgeId => exemptions.increment.as(Some(edgeId)),
      )
