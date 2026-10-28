package versola.central.configuration.clients.est

import versola.central.configuration.clients.ClientId
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.{Controller, Observability, Unauthorized}
import zio.*
import zio.http.*
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*

import java.time.Instant

/** EST (RFC 7030) for a client's mTLS certificate, the central side: the operator's endpoints (a one-time token, an
  * extension of the chain) and the two internal ones auth calls after it has authenticated the EST request. Auth holds no
  * CA power: what it asks for here, central decides and issues.
  *
  * The internal endpoints are for auth's token alone: an edge's is refused, and they are not served on the mutual-TLS
  * listener banks reach.
  */
object ClientEstController extends Controller:
  type Env = Tracing & ClientEstService & ResourceService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    createEnrollmentTokenEndpoint,
    extendEndpoint,
    revokeEndpoint,
    enrollEndpoint,
    renewEndpoint,
  )

  /** A one-time token for the named client to trade for its first certificate, shown once. */
  val createEnrollmentTokenEndpoint =
    Method.POST / "configuration" / "clients" / "est" / "enrollment-tokens" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        clientId <- request.url.queryZIO[ClientId]("clientId")
        hours = request.url.queryParams.getAll("validityHours").headOption
        response <- hours.map(_.toLongOption) match
          case Some(None) => ZIO.succeed(Response.text("validityHours is a whole number of hours").status(Status.BadRequest))
          case parsed =>
            val validity = parsed.flatten.fold(ClientEstService.TokenValidity)(h => Duration.fromSeconds(h.max(0L).min(1_000_000L) * 3600L))
            ZIO.serviceWithZIO[ClientEstService](_.issueEnrollmentToken(clientId, validity)).map: issued =>
              Response.json(EstEnrollmentTokenResponse(issued.token, issued.expiresAt, issued.subject).toJson).status(Status.Created)
            .catchSome:
              case error: ClientEstService.Refused => ZIO.succeed(Response.text(error.getMessage).status(statusOf(error)))
      yield response
    }

  /** Starts the client's chain over, for an operator who left `client-identity.auto-extend` off. */
  val extendEndpoint =
    Method.POST / "configuration" / "clients" / "est" / "extend" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        clientId <- request.url.queryZIO[ClientId]("clientId")
        response <- ZIO.serviceWithZIO[ClientEstService](_.extend(clientId)).as(Response.status(Status.NoContent))
          .catchSome:
            case error: ClientEstService.Refused => ZIO.succeed(Response.text(error.getMessage).status(Status.NotFound))
      yield response
    }

  /** Ends the right of the client's certificates to renew, for an operator who suspects the key is out. */
  val revokeEndpoint =
    Method.POST / "configuration" / "clients" / "est" / "revoke" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        clientId <- request.url.queryZIO[ClientId]("clientId")
        response <- ZIO.serviceWithZIO[ClientEstService](_.revoke(clientId)).as(Response.status(Status.NoContent))
          .catchSome:
            case error: ClientEstService.Refused => ZIO.succeed(Response.text(error.getMessage).status(Status.NotFound))
      yield response
    }

  /** Internal, for auth: the first certificate, for a token. */
  val enrollEndpoint =
    Method.POST / "est" / "enroll" -> handler { (request: Request) =>
      (for
        _ <- authorizeAuth(request)
        body <- request.bodyAs[EstEnrollRequest]
        issued <- ZIO.serviceWithZIO[ClientEstService](_.enroll(body.token, body.clientId, body.csr))
      yield Response.json(EstIssuedResponse(issued.chain, issued.chainEndsAt).toJson))
        .catchAll(failure)
    }

  /** Internal, for auth: a new certificate, for the certificate the client presented on the mutual-TLS listener. */
  val renewEndpoint =
    Method.POST / "est" / "renew" -> handler { (request: Request) =>
      (for
        _ <- authorizeAuth(request)
        body <- request.bodyAs[EstRenewRequest]
        issued <- ZIO.serviceWithZIO[ClientEstService](_.renew(body.presentedCertificate, body.csr))
      yield Response.json(EstIssuedResponse(issued.chain, issued.chainEndsAt).toJson))
        .catchAll(failure)
    }

  /** Auth's own token: one that names an edge is refused. */
  private def authorizeAuth(request: Request) =
    authorizeInternal(request).flatMap(edgeId => ZIO.fail(Unauthorized).when(edgeId.isDefined))

  private def statusOf(error: ClientEstService.Refused): Status = error.kind match
    case ClientEstService.Refusal.BadRequest => Status.BadRequest
    case ClientEstService.Refusal.Forbidden | ClientEstService.Refusal.ChainClosed => Status.Forbidden

  private def failure(error: ClientEstService.Refused | ClientEstService.InvalidCredentials.type | Throwable): ZIO[Any, Throwable, Response] =
    error match
      case ClientEstService.InvalidCredentials =>
        Observability.setError("est_unauthorized") *> ZIO.succeed(Response.status(Status.Unauthorized))
      case refused: ClientEstService.Refused =>
        Observability.setError(
          refused.kind match
            case ClientEstService.Refusal.BadRequest => "est_bad_request"
            case ClientEstService.Refusal.Forbidden => "est_forbidden"
            case ClientEstService.Refusal.ChainClosed => "est_chain_fork_detected",
          Some(refused.getMessage),
        ) *> ZIO.succeed(Response.text(refused.getMessage).status(statusOf(refused)))
      case other: Throwable => ZIO.fail(other)

case class EstEnrollmentTokenResponse(
    token: String,
    expiresAt: Instant,
    /** What the certificate request must say: the subject the certificate will carry. */
    subject: String,
) derives JsonCodec, Schema

/** `clientId` is the user name of HTTP Basic where the caller sent one; a Bearer token carries none. */
case class EstEnrollRequest(
    token: String,
    clientId: Option[ClientId],
    /** PKCS#10, PEM. */
    csr: String,
) derives JsonCodec, Schema

case class EstRenewRequest(
    /** The leaf of the certificate the client presented on the mutual-TLS listener, PEM. */
    presentedCertificate: String,
    /** PKCS#10, PEM. */
    csr: String,
) derives JsonCodec, Schema

/** The certificate chain (leaf first, no key) and, where the chain of the client ends, when. */
case class EstIssuedResponse(
    certificate: String,
    chainEndsAt: Option[Instant],
) derives JsonCodec, Schema
