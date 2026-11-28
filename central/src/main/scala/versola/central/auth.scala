package versola.central

import com.nimbusds.jose.jwk.RSAKey
import versola.central.configuration.edges.{EdgeCertificate, EdgeGroupKind, EdgeId, EdgeRecord, EdgeService}
import versola.central.configuration.partners.{PartnerRecord, PartnerRegistry, PartnerStatus}
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.topology.{CellId, CellRegistry}
import versola.util.http.{Forbidden, Observability, Unauthorized}
import versola.util.{JWT, Secret}
import zio.http.{Handler, Header, Middleware, Request, Response, Routes}
import zio.json.ast.Json
import zio.json.{EncoderOps, JsonCodec, jsonField}
import zio.{UIO, ZIO}

import java.security.interfaces.RSAPublicKey

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

/** Who made an internal call, decided from the key that verified its token and never from a header (#1050). */
enum Caller:
  /** A registered edge: the token names it in `edge_id` and verifies against that edge's key. */
  case Edge(id: EdgeId)

  /** The `auth` of a registered cell: the token verified with one of the cell's `publicKeys`, which is the key the secrets of
    * the answer are encrypted to. */
  case Cell(id: CellId, key: RSAPublicKey)

  /** The `auth` central is configured with (`auth.public-keys`), when no registered cell holds the key it signed with. */
  case ConfiguredAuth(key: RSAPublicKey)

  def edgeId: Option[EdgeId] = this match
    case Edge(id) => Some(id)
    case _ => None

  /** The key an `auth` caller proved itself with; an edge's is the one registered for it. */
  def authKey: Option[RSAPublicKey] = this match
    case Cell(_, key) => Some(key)
    case ConfiguredAuth(key) => Some(key)
    case _: Edge => None

/** The audience of what `auth` sends central, and the issuer of it. */
private val InternalAuthIssuer = "auth"
private val InternalAuthAudience = "central"

/** What an edge's token says of itself: `edge` issued it (`CentralSyncTokenService`, `PartnerEnrollment`) for `central`. */
private val InternalEdgeIssuer = "edge"
private val InternalEdgeAudience = "central"

/** Who calls (#1050). A token with an `edge_id` header is an edge's, checked against that edge's key and, like an `auth`'s, as RS256
  * and nothing else, with an `exp`, `iss` `edge` and `aud` `central`: what every edge signs (a token that lacks one of them was
  * made by nothing in this repository, and is refused). Any other
  * is an `auth`'s: its `kid` finds the public key among the keys of the registered cells and `auth.public-keys`, the signature
  * is verified with it as RS256 and nothing else (a token under the old shared secret, HS256, is refused), and `iss` is `auth`,
  * `aud` has `central` and it has not expired. The caller is the cell whose key verified, else the configured `auth`; a key in
  * both is the cell's. A `cell_id` header, which is only the cell's claim about itself, has to agree with the cell the key says.
  */
def authorizeInternal(request: Request): ZIO[CentralConfig & EdgeService & CellRegistry, Unauthorized.type, Caller] =
  request.header(Header.Authorization) match
    case Some(Header.Authorization.Bearer(token)) =>
      val raw = token.stringValue
      for
        header <- JWT.parseHeader[InternalAuthHeader](raw).orElseFail(Unauthorized)
        caller <- header.edgeId.map(EdgeId(_)) match
          case Some(id) =>
            for
              edgeService <- ZIO.service[EdgeService]
              edge <- edgeService.find(id).someOrFail(Unauthorized)
              // RS256 and nothing else, as for an `auth`: an RSA key verifies PS256 too, which [[JWT.deserialize]] would accept
              _ <- ZIO.fail(Unauthorized).unless(header.alg.contains("RS256"))
              claims <- JWT.deserialize[InternalAuthClaims](raw, edge.asPublicKeys, JWT.Type.JWT)
                .orElseFail(Unauthorized)
              // [[JWT.deserialize]] takes a token with no `exp` for one that does not expire; an edge's expires (ten minutes)
              _ <- ZIO.fail(Unauthorized).unless(
                claims.exp.isDefined && claims.iss.contains(InternalEdgeIssuer) && claims.audiences.contains(InternalEdgeAudience),
              )
            yield Caller.Edge(id)
          case None => authorizeAuthToken(raw, header)
      yield caller

    case _ =>
      ZIO.fail(Unauthorized)

private def authorizeAuthToken(raw: String, header: InternalAuthHeader): ZIO[CentralConfig & CellRegistry, Unauthorized.type, Caller] =
  for
    // RS256 and nothing else: an RSA key verifies PS256 too, which [[JWT.deserialize]] would accept under it
    _ <- ZIO.fail(Unauthorized).unless(header.alg.contains("RS256"))
    kid <- ZIO.fromOption(header.kid.filter(_.nonEmpty)).orElseFail(Unauthorized)
    config <- ZIO.service[CentralConfig]
    cells <- ZIO.serviceWithZIO[CellRegistry](_.all)
    // the registered cells' keys first: a key in both places is the cell's
    candidates =
      cells.toList.flatMap(cell => cell.publicKeys.filter(keyIdOf(_).contains(kid)).map(key => (Option(cell.id), key))) ++
        config.auth.publicKeys.filter(keyIdOf(_).contains(kid)).map(key => (Option.empty[CellId], key))
    verified <- firstVerifying(raw, candidates).someOrFail(Unauthorized)
    (cell, key, claims) = verified
    // [[JWT.deserialize]] takes a token with no `exp` for one that does not expire; a token that stands in for a service must expire
    _ <- ZIO.fail(Unauthorized).unless(
      claims.exp.isDefined && claims.iss.contains(InternalAuthIssuer) && claims.audiences.contains(InternalAuthAudience),
    )
    publicKey <- ZIO.attempt(RSAKey.parse(key.toJson).toRSAPublicKey).orElseFail(Unauthorized)
    caller <- cell match
      case Some(id) =>
        // the cell the key says; what the token says of itself must agree
        ZIO.fail(Unauthorized).when(header.cellId.exists(_ != id)).as(Caller.Cell(id, publicKey))
      case None => ZIO.succeed(Caller.ConfiguredAuth(publicKey))
  yield caller

private def keyIdOf(key: Json.Obj): Option[String] =
  key.fields.collectFirst { case ("kid", Json.Str(kid)) => kid }

/** The first of `candidates` whose key verifies the token, with the signature, the type and the expiry checked by
  * [[JWT.deserialize]], and the claims it carried. */
private def firstVerifying(
    raw: String,
    candidates: List[(Option[CellId], Json.Obj)],
): UIO[Option[(Option[CellId], Json.Obj, InternalAuthClaims)]] =
  ZIO.collectFirst(candidates): (cell, key) =>
    // a stored key that is not a JWK is a key that verifies nothing, not a failed request
    ZIO.attempt(JWT.PublicKeys.fromJson(Json.Obj("keys" -> Json.Arr(key))))
      .flatMap(keys => JWT.deserialize[InternalAuthClaims](raw, keys, JWT.Type.JWT))
      .map(claims => Option((cell, key, claims)))
      .orElseSucceed(None)

/** A route that accepts an `auth` and refuses an edge: the caller is the `Cell` or the `ConfiguredAuth`. */
def authorizeAuth(request: Request): ZIO[CentralConfig & EdgeService & CellRegistry, Unauthorized.type, Caller] =
  authorizeInternal(request).flatMap:
    case _: Caller.Edge => ZIO.fail(Unauthorized)
    case caller => ZIO.succeed(caller)

/** The edge-facing surface's check (#554): the caller is a registered edge, proven twice. The key
  * it signed the token with says which edge it claims to be; the client certificate the TLS
  * handshake presented, on central's mutual-TLS listener, must have been issued to that same edge
  * ([[EdgeCertificate.isIssuedTo]]). A certificate for another edge is therefore not enough to
  * use a token that names this one, and a stolen token is not enough without a certificate. The
  * caller is served as that edge and no other. Auth's token names no edge and is refused: what auth
  * needs, it reads under `/configuration`.
  */
def authorizeEdge(request: Request): ZIO[CentralConfig & EdgeService & CellRegistry, Unauthorized.type, EdgeId] =
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
def authorizeAnyEdge(request: Request): ZIO[CentralConfig & EdgeService & CellRegistry, Unauthorized.type, EdgeRecord] =
  for
    edgeId <- authorizeInternal(request).map(_.edgeId).someOrFail(Unauthorized)
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
def authorizePartnerEdge(request: Request)
    : ZIO[CentralConfig & EdgeService & CellRegistry & PartnerRegistry, Unauthorized.type | Forbidden.type, PartnerCaller] =
  for
    edge <- authorizeAnyEdge(request)
    _ <- (Observability.setError("edge_group_first_party") *> ZIO.fail(Unauthorized)).when(edge.groupKind != EdgeGroupKind.Partner)
    // a partner's edge presents a certificate that names its group: one that names only the edge says nothing of who it speaks for
    _ <- (Observability.setError("edge_certificate_without_group") *> ZIO.fail(Unauthorized)).unless(
      request.remoteCertificate.exists(EdgeCertificate.isIssuedToMemberOf(_, edge.id, edge.groupId)),
    )
    partner <- unsuspendedPartnerOf(edge)
  yield PartnerCaller(edge, partner)

/** The route both kinds of edge reach, `POST /edge/certificate/renew`: [[authorizeAnyEdge]], and an edge of a partner group is
  * refused with 403 `partner_suspended` while its partner is suspended, as on every route of `/edge/partner` (#962: the kill
  * switch stops the certificate from being renewed too). An edge of a first-party group is not looked at beyond the first.
  */
def authorizeRenewal(request: Request)
    : ZIO[CentralConfig & EdgeService & CellRegistry & PartnerRegistry, Unauthorized.type | Forbidden.type, EdgeRecord] =
  for
    edge <- authorizeAnyEdge(request)
    _ <- unsuspendedPartnerOf(edge).when(edge.groupKind == EdgeGroupKind.Partner)
  yield edge

private def unsuspendedPartnerOf(edge: EdgeRecord): ZIO[PartnerRegistry, Unauthorized.type | Forbidden.type, PartnerRecord] =
  for
    partner <- ZIO.serviceWithZIO[PartnerRegistry](_.findByGroup(edge.groupId))
      .someOrElseZIO(Observability.setError("partner_unknown") *> ZIO.fail(Unauthorized))
    _ <- (Observability.setError("partner_suspended") *> ZIO.fail(Forbidden)).when(partner.status == PartnerStatus.Suspended)
  yield partner

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
val refuseEdgeCallers: Middleware[CentralConfig & EdgeService & CellRegistry] = new Middleware[CentralConfig & EdgeService & CellRegistry]:
  def apply[Env1 <: CentralConfig & EdgeService & CellRegistry, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
    Routes.fromIterable(routes.routes.map(_.transform(refuse)))

  private def refuse[Env1 <: CentralConfig & EdgeService & CellRegistry](
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

/** What is read of a token's header before anything is verified: which edge it says it is from, and the `alg`, `kid` and
  * `cell_id` of an `auth`'s. */
case class InternalAuthHeader(
    @jsonField("edge_id") edgeId: Option[String],
    alg: Option[String],
    kid: Option[String],
    @jsonField("cell_id") cellId: Option[String],
) derives JsonCodec

/** The claims central looks at, of a token that has been verified: its `iss`, `aud` and that it has an `exp`. `aud` is a string or a
  * list of them (RFC 7519 §4.1.3). */
case class InternalAuthClaims(
    iss: Option[String],
    aud: Option[Json],
    exp: Option[Long],
) derives JsonCodec:
  def audiences: List[String] = aud match
    case Some(Json.Str(single)) => List(single)
    case Some(Json.Arr(many)) => many.collect { case Json.Str(each) => each }.toList
    case _ => Nil
