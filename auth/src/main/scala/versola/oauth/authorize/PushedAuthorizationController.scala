package versola.oauth.authorize

import versola.oauth.authorize.model.{PushedAuthorizationError, PushedAuthorizationErrorResponse}
import versola.util.http.{Controller, extractCredentials}
import versola.util.CoreConfig
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

/**
 * OAuth 2.0 Pushed Authorization Requests
 * RFC 9126: https://datatracker.ietf.org/doc/html/rfc9126
 */
object PushedAuthorizationController extends Controller:
  type Env = Tracing & PushedAuthorizationService & CoreConfig

  def routes: Routes[Env, Throwable] = Routes(
    parEndpoint,
    parMethodNotAllowed,
  )

  val parEndpoint =
    Method.POST / "par" -> handler { (request: Request) =>
      (for
        config <- ZIO.service[CoreConfig]
        service <- ZIO.service[PushedAuthorizationService]

        form <- readForm(request, config.parOrDefault.maxRequestSize)
        credentials <- request.extractCredentials(form).orElseFail(PushedAuthorizationError.InvalidClient)

        response <- service.push(AuthorizeRequestParser.paramsFromForm(form), credentials, request)
      yield Response.json(response.toJson)
        .status(Status.Created)
        .addHeader(Header.CacheControl.NoStore))
        .catchAll {
          case error: PushedAuthorizationError => ZIO.succeed(errorResponse(error))
          case error: Throwable => ZIO.fail(error)
        }
    }

  /** RFC 9126 §2.3: anything but POST is answered with 405 rather than the router's 404. */
  val parMethodNotAllowed =
    Method.ANY / "par" -> handler { (request: Request) =>
      if request.method == Method.POST then ZIO.succeed(Response.notFound)
      else ZIO.succeed(errorResponse(PushedAuthorizationError.MethodNotAllowed))
    }

  /** RFC 9126 §2.3: an oversized pushed request is rejected with 413 before it is parsed.
    * The body is read as a stream bounded by the limit, so a request that understates or
    * omits its length (chunked transfer encoding) cannot be buffered in full.
    */
  private def readForm(request: Request, maxRequestSize: Int): IO[PushedAuthorizationError, Form] =
    val declaredSize = request.header(Header.ContentLength).map(_.length)
    for
      _ <- ZIO.fail(PushedAuthorizationError.RequestTooLarge)
        .when(declaredSize.exists(_ > maxRequestSize))
      body <- request.body.asStream.take(maxRequestSize.toLong + 1).runCollect
        .orElseFail(PushedAuthorizationError.Validation(
          error = "invalid_request",
          errorDescription = Some("The pushed authorization request body could not be read"),
          errorUri = None,
        ))
      _ <- ZIO.fail(PushedAuthorizationError.RequestTooLarge).when(body.length > maxRequestSize)
      form <- ZIO.fromEither(Form.fromURLEncoded(String(body.toArray, Charsets.Utf8), Charsets.Utf8))
        .orElseFail(PushedAuthorizationError.Validation(
          error = "invalid_request",
          errorDescription = Some("The pushed authorization request body is not a valid form"),
          errorUri = None,
        ))
    yield form

  private def errorResponse(error: PushedAuthorizationError): Response =
    val response = Response
      .json(PushedAuthorizationErrorResponse.from(error).toJson)
      .status(error.status)
      .addHeader(Header.CacheControl.NoStore)
    // RFC 6749 §5.2: a 401 response to a client-authenticated endpoint must include the
    // WWW-Authenticate challenge for the scheme the client is expected to use.
    error match
      case PushedAuthorizationError.InvalidClient =>
        response.addHeader(Header.WWWAuthenticate.Basic(realm = None))
      case _ =>
        response
