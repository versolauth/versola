package versola.oauth.authorize

import versola.http.Controller
import versola.oauth.authorize.model.RenderableError
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

object AuthorizeEndpointController extends Controller:
  type Env = Tracing & AuthorizeRequestParser

  def routes: Routes[Env, Nothing] = Routes(
    getAuthorizeRoute,
    postAuthorizeRoute
  )

  val getAuthorizeRoute = authorize(Method.GET)
  val postAuthorizeRoute = authorize(Method.POST)

  def authorize(method: Method): Route[Tracing & AuthorizeRequestParser, Nothing] =
    method / "api" / "v1" / "authorize" -> handler { (request: Request) =>
      for
        parser <- ZIO.service[AuthorizeRequestParser]
        params <- extractRequestParams(request)
        parsedRequest <- parser.parse(params)
      yield Response.html("<html><body>Hello World!</body></html>")
    }.catchAll {
      case error: RenderableError =>
        handler:
          error.redirectUriWithErrorParams match
            case Some(uri) =>
              Response.seeOther(uri)
            case None =>
              Response.badRequest(error.error.errorDescription)

      case ex: Throwable =>
        handler(Response.internalServerError)
    }

  private def extractRequestParams(request: Request): Task[Map[String, Chunk[String]]] =
    request.method match
      case Method.GET =>
        ZIO.succeed(request.url.queryParams.map)
      case Method.POST | _ =>
        request.body.asURLEncodedForm
          .map(_.formData.flatMap(fd => fd.stringValue.map(v => fd.name -> Chunk(v))).toMap)


  val layer: ZLayer[Env, Nothing, Routes[Any, Nothing]] =
    ZLayer:
      for
        tracing <- ZIO.service[Tracing]
        parser <- ZIO.service[AuthorizeRequestParser]
      yield routes.provideEnvironment(
        ZEnvironment(tracing) ++ ZEnvironment(parser)
      )
