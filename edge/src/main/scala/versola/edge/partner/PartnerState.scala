package versola.edge.partner

import versola.edge.{CentralSyncTokenService, EdgeConfig}
import versola.util.{CacheSource, ReloadingCache}
import zio.http.{Client, Header, Request, Status}
import zio.json.ast.Json
import zio.json.{DecoderOps, JsonCodec}
import zio.{Ref, Scope, Task, UIO, URLayer, ZIO, ZLayer}

/** What central says about this edge's partner at `GET /edge/partner/me` (#962 §4): the partner, where the operator's services are,
  * the console's client and preset, and this edge. Kept as central sends it: the console is shown what the operator recorded, and
  * the edge decides nothing from `limits`.
  */
case class PartnerMe(
    partner: PartnerMe.Partner,
    operator: PartnerMe.Operator,
    console: PartnerMe.Console,
    edge: PartnerMe.Edge,
) derives JsonCodec

object PartnerMe:
  case class Partner(
      id: String,
      displayName: String,
      logoUri: Option[String],
      status: String,
      limits: Json,
      reviewAt: Option[String],
      tenantId: String,
  ) derives JsonCodec

  case class Operator(name: String, issuer: Option[String], mtlsUrl: Option[String], centralMtlsUrl: Option[String]) derives JsonCodec

  /** The console signs in at `/login/<presetId>` and out at `/logout/<presetId>`; `clientId` is the OAuth client of its sessions. */
  case class Console(clientId: String, presetId: String) derives JsonCodec

  case class Edge(id: String, publicUrl: Option[String]) derives JsonCodec

/** `/edge/partner/me` read through the configuration snapshot (#566), so that a partner edge starts and serves its sessions while
  * central is unreachable. */
trait PartnerMeSyncClient extends CacheSource[PartnerMe]

object PartnerMeSyncClient:
  /** The answer of central says this edge is not a partner edge: its group is the operator's own (`401`, `edge_group_first_party`). */
  case class KindMismatch(configured: String)
    extends IllegalStateException(
      s"mode = $configured, but central does not hold this edge in a group of that kind: a first-party edge is configured " +
        "mode = first-party, and a partner's edge mode = partner (central refused /edge/partner/me for the edge's group). " +
        "Check mode (the group of this edge is the one it enrolled in).",
    )

  /** Central refuses this partner's edges everything (`403`, `partner_suspended`): the operator has suspended it. */
  case object Suspended
    extends IllegalStateException("central answers 403 to this partner edge: the partner is suspended, or this edge is not registered in its group")

  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, PartnerMeSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(httpClient: Client, config: EdgeConfig, tokens: CentralSyncTokenService) extends PartnerMeSyncClient:
    private val MeURL = config.partnerUrl / "me"

    override def getAll: Task[PartnerMe] =
      for
        token <- tokens.getToken
        request = Request.get(MeURL).addHeader(Header.Authorization.Bearer(token))
        outcome <- ZIO.scoped(tokens.syncRequest(httpClient, request).flatMap(response => response.body.asString.map(response.status -> _)))
        (status, body) = outcome
        me <- status match
          case Status.Ok =>
            ZIO.fromEither(body.fromJson[PartnerMe]).mapError(error => RuntimeException(s"central's /edge/partner/me is not readable: $error"))
          case Status.Unauthorized => ZIO.fail(KindMismatch(config.mode.name))
          case Status.Forbidden => ZIO.fail(Suspended)
          case other => ZIO.fail(RuntimeException(s"central answered ${other.code} to /edge/partner/me"))
      yield me

/** The partner this edge belongs to, as the last successful read of central has it, and whether central answered the last one.
  *
  * The console is served from this: `/partner/api/me`, the console's preset for `/partner/login` and `/partner/logout`, the
  * identity a staff token is held to (its `client_id` is the console's client). While central is unreachable the value is the one
  * last read, or the snapshot's, and [[centralReachable]] says so.
  */
trait PartnerState:
  def me: UIO[PartnerMe]

  /** Whether the last attempt to read central succeeded. */
  def centralReachable: UIO[Boolean]

  /** Central has refused this partner's edge on a call other than the refresh of `me`: the partner is suspended, until a read says otherwise. */
  def noteSuspended: UIO[Unit]

object PartnerState:
  val live: ZLayer[Scope & PartnerMeSyncClient & EdgeConfig, Throwable, PartnerState] =
    ZLayer.fromZIO:
      for
        source <- ZIO.service[PartnerMeSyncClient]
        config <- ZIO.service[EdgeConfig]
        reachable <- Ref.make(true)
        suspended <- Ref.make(false)
        tracking = new CacheSource[PartnerMe]:
          override def getAll: Task[PartnerMe] =
            source.getAll.tap(_ => reachable.set(true) *> suspended.set(false)).tapError:
              // central answered, and refused: the partner is suspended, which the console is told rather than left with the last `active`
              case PartnerMeSyncClient.Suspended => reachable.set(true) *> suspended.set(true)
              case _ => reachable.set(false)
        cache <- ReloadingCache.make[PartnerMe](config.configurationCacheRefreshInterval, fromSnapshot = true)
          .provideSomeLayer[Scope](ZLayer.succeed[CacheSource[PartnerMe]](tracking))
      yield new PartnerState:
        override def me: UIO[PartnerMe] =
          cache.get.zipWith(suspended.get)((me, suspended) => if suspended then me.copy(partner = me.partner.copy(status = "suspended")) else me)
        override def centralReachable: UIO[Boolean] = reachable.get
        override def noteSuspended: UIO[Unit] = suspended.set(true)

  /** The answer for a first-party edge, which has no partner. */
  def fixed(value: PartnerMe, reachable: Boolean): PartnerState =
    new PartnerState:
      override def me: UIO[PartnerMe] = ZIO.succeed(value)
      override def centralReachable: UIO[Boolean] = ZIO.succeed(reachable)
      override def noteSuspended: UIO[Unit] = ZIO.unit

/** The first read of `/edge/partner/me`, before anything else of the configuration is: a partner edge central does not recognise as
  * one (or has suspended) stops here, naming why, instead of in the first of the caches that is refused. An edge that cannot reach
  * central at all is let through: its caches wait for central and fall back on the snapshot, and the answer to the question is read
  * once central is back (the refresh of [[PartnerState]]).
  */
object PartnerMeCheck:
  val live: ZLayer[PartnerMeSyncClient, Throwable, PartnerMeCheck] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[PartnerMeSyncClient](
        _.getAll.unit.catchSome:
          // anything but the two refusals above is central not being there, or not saying: the caches and the snapshot deal with that
          case error if !error.isInstanceOf[PartnerMeSyncClient.KindMismatch] && error != PartnerMeSyncClient.Suspended => ZIO.unit,
      ).as(new PartnerMeCheck {})

trait PartnerMeCheck
