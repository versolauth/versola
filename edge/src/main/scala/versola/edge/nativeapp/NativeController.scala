package versola.edge.nativeapp

import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.json.{EncoderOps, JsonEncoder, jsonField}

/** `POST /native/{start,complete,token,revoke}/{clientId}` (#420). One set per client, the way
  * `/login/{presetId}` is one per preset: the app never chooses its client by any other means.
  */
object NativeController extends Controller:
  type Env = NativeService

  /** RFC 6749 §5.2 error object, the shape auth's own refusals are relayed in. */
  private case class ErrorBody(
      error: String,
      @jsonField("error_description") errorDescription: Option[String],
  ) derives JsonEncoder

  def routes: Routes[Env, Throwable] = Routes(
    endpoint("start")(_.start),
    endpoint("complete")(_.complete),
    endpoint("token")(_.refresh),
    endpoint("revoke")(_.revoke),
  )

  private def endpoint(name: String)(
      call: NativeService => (String, Request) => IO[NativeError | Throwable, Response],
  ): Route[Env, Throwable] =
    Method.POST / "native" / name / string("clientId") -> handler { (clientId: String, request: Request) =>
      ZIO.serviceWithZIO[NativeService](service => call(service)(clientId, request))
        .catchAll {
          case error: NativeError => ZIO.succeed(render(error))
          case error: Throwable => ZIO.fail(error)
        }
    }

  def render(error: NativeError): Response =
    def oauth(code: String, description: Option[String]) =
      Response.json(ErrorBody(code, description).toJson)
        .status(Status.BadRequest)
        .addHeader(Header.CacheControl.NoStore)
    error match
      // Cached like every other answer here: a 404 names which client ids this edge fronts,
      // and an intermediary holding one would go on answering it after the client is created.
      case NativeError.UnknownClient => Response.notFound.addHeader(Header.CacheControl.NoStore)
      case NativeError.InvalidRequest(description) => oauth("invalid_request", Some(description))
      case NativeError.UnsupportedGrantType =>
        oauth("unsupported_grant_type", Some("only refresh_token is served here"))
      case NativeError.InvalidDpopProof(description) => oauth("invalid_dpop_proof", Some(description))
      case NativeError.InvalidGrant(description) => oauth("invalid_grant", Some(description))
