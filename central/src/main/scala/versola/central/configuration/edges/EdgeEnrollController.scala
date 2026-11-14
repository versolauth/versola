package versola.central.configuration.edges

import versola.central.CentralConfig
import versola.central.configuration.metadata.ServerMetadataService
import versola.util.http.{Controller, Observability}
import zio.http.*
import zio.json.ast.Json
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*
import zio.{URIO, ZIO}

/** The one route an edge reaches before it has a certificate: it trades the one-time token an operator gave it, a request for
  * the certificate the operator's registration entitles it to and, for an edge registered without a signing key, the public half
  * of the key it generated, for that certificate's chain and for everything else the edge needs to know to start (#962).
  *
  * Served on its own listener, behind the TLS terminator of the public name for central, and nothing else is served
  * there. There is no client certificate to check yet; the token is the credential, and an unknown, spent, expired or
  * other-edge token is one and the same `401`.
  */
object EdgeEnrollController extends Controller:
  type Env = Tracing & EdgeIdentityService & CentralConfig & ServerMetadataService

  def routes: Routes[Env, Throwable] = Routes(enrollEndpoint)

  val enrollEndpoint =
    Method.POST / "edge" / "enroll" -> handler { (request: Request) =>
      (for
        body <- request.bodyAs[EnrollEdgeRequest]
        identity <- ZIO.serviceWithZIO[EdgeIdentityService](_.enroll(body.edgeId, body.token, body.csr, body.signingKey))
        response <- EnrollEdgeResponse.of(identity)
      yield Response.json(response.toJson))
        .catchAll:
          case EdgeIdentityService.InvalidToken =>
            Observability.setError("edge_enrollment_invalid_token") *> ZIO.succeed(Response.status(Status.Unauthorized))
          case EdgeIdentityService.PartnerSuspended =>
            Observability.setError("partner_suspended") *> ZIO.succeed(Response.status(Status.Forbidden))
          case error: EdgeIdentityService.Refused =>
            Observability.setError("edge_enrollment_refused", Some(error.getMessage)) *>
              ZIO.succeed(Response.text(error.getMessage).status(Status.UnprocessableEntity))
          case error: Throwable => ZIO.fail(error)
    }

/** `edgeId` is optional: the token says which edge it is for, and an edge that names one (a first-party edge, as before) must name the
  * token's. `signingKey` is the edge's public JWK, required where the edge was registered without one. */
case class EnrollEdgeRequest(
    edgeId: Option[EdgeId] = None,
    token: String,
    csr: String,
    signingKey: Option[Json.Obj] = None,
) derives JsonCodec, Schema

/** What an edge learns when it enrols, besides its certificate: who it is and where central and auth are, so that it is configured
  * with the token alone. An address is absent where central has not been told its public names. */
case class EnrollEdgeResponse(
    certificate: String,
    edge: EnrollEdgeResponse.Edge,
    central: EnrollEdgeResponse.Central,
    auth: EnrollEdgeResponse.Auth,
) derives JsonCodec, Schema

object EnrollEdgeResponse:
  case class Edge(id: EdgeId, group: EdgeGroupId) derives JsonCodec, Schema
  case class Central(mtlsUrl: Option[String]) derives JsonCodec, Schema
  case class Auth(issuer: Option[String], mtlsUrl: Option[String]) derives JsonCodec, Schema

  def of(identity: EdgeIdentityService.Identity): URIO[CentralConfig & ServerMetadataService, EnrollEdgeResponse] =
    for
      config <- ZIO.service[CentralConfig]
      metadata <- ZIO.serviceWithZIO[ServerMetadataService](_.getMetadata)
      issuer = metadata.flatMap(_.get("issuer")).collect { case Json.Str(value) => value }
    yield EnrollEdgeResponse(
      certificate = identity.chain,
      edge = Edge(identity.edge.id, identity.edge.groupId),
      central = Central(config.publicUrls.map(_.mtls.encode)),
      auth = Auth(issuer, config.auth.mtlsUrl.map(_.encode)),
    )

/** What an edge is given for its enrollment and for every renewal: its certificate chain (leaf first, no key: it made the
  * key). */
case class EdgeIdentityResponse(
    certificate: String,
) derives JsonCodec, Schema
