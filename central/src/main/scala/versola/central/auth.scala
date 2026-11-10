package versola.central

import versola.central.configuration.edges.{EdgeCertificate, EdgeGroupKind, EdgeId, EdgeRecord, EdgeService}
import versola.central.configuration.partners.{PartnerRecord, PartnerRegistry, PartnerStatus}
import versola.central.configuration.resources.ResourceService
import versola.util.http.{Forbidden, Observability, Unauthorized}
import versola.util.{JWT, Secret}
import zio.ZIO
import zio.http.{Handler, Header, Middleware, Request, Response, Routes}
import zio.json.{JsonCodec, jsonField}

/** Verifies the central resource secret via HTTP Basic. */
def authorizeBasic(request: Request): ZIO[ResourceService, Unauthorized.type, Unit] =
  request.header(Header.Authorization) match
    case Some(Header.Authorization.Basic(username, password)) if username == "central" =>
      for
        provided <- ZIO.fromEither(Secret.fromBase64Url(password.stringValue)).orElseFail(Unauthorized)
        resourceService <- ZIO.service[ResourceService]
        valid <- resourceService.verifySecret(provided).orElseFail(Unauthorized)
        _ <- ZIO.unless(valid)(ZIO.fail(Unauthorized))
      yield ()

    case _ =>
      ZIO.fail(Unauthorized)

def authorizeInternal(request: Request): ZIO[CentralConfig & EdgeService, Unauthorized.type, Option[EdgeId]] =
  request.header(Header.Authorization) match
    case Some(Header.Authorization.Bearer(token)) =>
      val raw = token.stringValue
      for
        header <- JWT.parseHeader[InternalAuthHeader](raw).orElseFail(Unauthorized)
        edgeId = header.edgeId.map(EdgeId(_))
        _ <- edgeId match
          case Some(id) =>
            for
              edgeService <- ZIO.service[EdgeService]
              edge <- edgeService.find(id).someOrFail(Unauthorized)
              _ <- JWT.deserialize[InternalAuthClaims](raw, edge.asPublicKeys, JWT.Type.JWT)
                .orElseFail(Unauthorized)
            yield ()
          case None =>
            for
              config <- ZIO.service[CentralConfig]
              _ <- JWT.deserialize[InternalAuthClaims](raw, config.secretKey, JWT.Type.JWT)
                .orElseFail(Unauthorized)
            yield ()
      yield edgeId

    case _ =>
      ZIO.fail(Unauthorized)

/** The edge-facing surface's check (#554): the caller is a registered edge, proven twice. The key
  * it signed the token with says which edge it claims to be; the client certificate the TLS
  * handshake presented, on central's mutual-TLS listener, must have been issued to that same edge
  * ([[EdgeCertificate.isIssuedTo]]). A certificate for another edge is therefore not enough to
  * use a token that names this one, and a stolen token is not enough without a certificate. The
  * caller is served as that edge and no other. Auth's token names no edge and is refused: what auth
  * needs, it reads under `/configuration`.
  */
def authorizeEdge(request: Request): ZIO[CentralConfig & EdgeService, Unauthorized.type, EdgeId] =
  for
    edge <- authorizeAnyEdge(request)
    // What a partner's edge may do is decided by the operator's registration of its group, not by anything the edge
    // presents: it gets a certificate that names its group and nothing from central's configuration. Its own surface is
    // `/edge/partner` ([[authorizePartnerEdge]]).
    _ <- (Observability.setError("edge_group_partner") *> ZIO.fail(Unauthorized))
      .when(edge.groupKind != EdgeGroupKind.FirstParty)
  yield edge.id

/** The two checks of [[authorizeEdge]] without the kind check: the caller is a registered edge of either kind, proven by
  * its token and by the certificate the handshake presented. For the one route both kinds reach, `POST
  * /edge/certificate/renew`: a partner's edge renews its certificate for as long as it is registered (#549 decision 8),
  * and what else it may do is decided by the surface, not here.
  */
def authorizeAnyEdge(request: Request): ZIO[CentralConfig & EdgeService, Unauthorized.type, EdgeRecord] =
  for
    edgeId <- authorizeInternal(request).someOrFail(Unauthorized)
    edge <- ZIO.serviceWithZIO[EdgeService](_.find(edgeId)).someOrFail(Unauthorized)
    presented <- ZIO.fromOption(request.remoteCertificate).orElseFail(Unauthorized)
    _ <- ZIO.fail(Unauthorized).unless(EdgeCertificate.isIssuedTo(presented, edgeId, edge.groupId))
  yield edge

/** The partner-edge surface's check (#549, #962), for `/edge/partner/...`: the two checks of [[authorizeAnyEdge]], and the
  * caller is an edge of a `partner` group (else 401 `edge_group_first_party`: the operator's own edges read `/edge/sync`)
  * whose partner exists (`edge -> group -> partner`, else 401) and is not suspended (else 403 `partner_suspended`, on every
  * call, certificate renewal included -- the kill switch). An `onboarding` partner is served: its first edge calling is what
  * makes it `active`, which `/edge/partner/me` does.
  */
def authorizePartnerEdge(request: Request): ZIO[CentralConfig & EdgeService & PartnerRegistry, Unauthorized.type | Forbidden.type, PartnerCaller] =
  for
    edge <- authorizeAnyEdge(request)
    _ <- (Observability.setError("edge_group_first_party") *> ZIO.fail(Unauthorized)).when(edge.groupKind != EdgeGroupKind.Partner)
    partner <- ZIO.serviceWithZIO[PartnerRegistry](_.findByGroup(edge.groupId))
      .someOrElseZIO(Observability.setError("partner_unknown") *> ZIO.fail(Unauthorized))
    _ <- (Observability.setError("partner_suspended") *> ZIO.fail(Forbidden)).when(partner.status == PartnerStatus.Suspended)
  yield PartnerCaller(edge, partner)

/** An edge of a partner, as `authorizePartnerEdge` proved it: the edge that called and the partner it belongs to. */
case class PartnerCaller(edge: EdgeRecord, partner: PartnerRecord)

/** Refuses an edge token on every route it wraps (#554). The edge-facing surface is
  * `EdgeSyncController` alone; everything else central serves belongs to auth or to the admin
  * console, so an edge is turned away once, here, rather than in each controller -- an endpoint
  * added later cannot forget to. A token that names an edge is checked the way
  * `authorizeInternal` would: a genuine one is refused as forbidden, a forged one as
  * unauthenticated. A token that names no edge, or no bearer token at all, is the route's own
  * business.
  */
val refuseEdgeCallers: Middleware[CentralConfig & EdgeService] = new Middleware[CentralConfig & EdgeService]:
  def apply[Env1 <: CentralConfig & EdgeService, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
    Routes.fromIterable(routes.routes.map(_.transform(refuse)))

  private def refuse[Env1 <: CentralConfig & EdgeService](
      handler: Handler[Env1, Response, Request, Response],
  ): Handler[Env1, Response, Request, Response] =
    Handler.scoped[Env1]:
      Handler.fromFunctionZIO[Request]: request =>
        request.header(Header.Authorization) match
          case Some(Header.Authorization.Bearer(token)) =>
            JWT.parseHeader[InternalAuthHeader](token.stringValue).map(_.edgeId.isDefined).orElseSucceed(false).flatMap:
              case false => handler(request)
              case true =>
                authorizeInternal(request).foldZIO(
                  _ => ZIO.fail(Response.unauthorized),
                  _ => Observability.setError("edge_caller_refused") *> ZIO.fail(Response.forbidden),
                )
          case _ => handler(request)

case class InternalAuthHeader(
    @jsonField("edge_id") edgeId: Option[String],
) derives JsonCodec

case class InternalAuthClaims() derives JsonCodec
