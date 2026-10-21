package versola.central.configuration.edges

import versola.central.configuration.{SignEdgeCertificateResponse}
import versola.util.http.{Controller, Observability}
import zio.ZIO
import zio.http.*
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*

/** The one route an edge reaches before it has a certificate: it trades the one-time token an operator gave it, and a
  * request for the certificate the operator's registration entitles it to, for that certificate's chain.
  *
  * Served on its own listener, behind the TLS terminator of the public name for central, and nothing else is served
  * there. There is no client certificate to check yet; the token is the credential, and an unknown, spent, expired or
  * other-edge token is one and the same `401`.
  */
object EdgeEnrollController extends Controller:
  type Env = Tracing & EdgeIdentityService

  def routes: Routes[Env, Throwable] = Routes(enrollEndpoint)

  val enrollEndpoint =
    Method.POST / "edge" / "enroll" -> handler { (request: Request) =>
      (for
        body <- request.bodyAs[EnrollEdgeRequest]
        chain <- ZIO.serviceWithZIO[EdgeIdentityService](_.enroll(body.edgeId, body.token, body.csr))
      yield Response.json(SignEdgeCertificateResponse(chain).toJson))
        .catchAll:
          case EdgeIdentityService.InvalidToken =>
            Observability.setError("edge_enrollment_invalid_token") *> ZIO.succeed(Response.status(Status.Unauthorized))
          case error: EdgeIdentityService.Refused =>
            Observability.setError("edge_enrollment_refused", Some(error.getMessage)) *>
              ZIO.succeed(Response.text(error.getMessage).status(Status.UnprocessableEntity))
          case error: Throwable => ZIO.fail(error)
    }

case class EnrollEdgeRequest(
    edgeId: EdgeId,
    token: String,
    csr: String,
) derives JsonCodec, Schema
