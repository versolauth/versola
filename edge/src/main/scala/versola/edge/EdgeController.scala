package versola.edge

import versola.edge.model.{AuthConversationNotFound, Code, PresetId, PresetNotFound, ResourceId, State}
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.json.EncoderOps

object EdgeController extends Controller:
  type Env = Tracing & EdgeService & EdgeConfig & JwksService

  def routes: Routes[Env, Throwable] = Routes(
    loginEndpoint,
    completeEndpoint,
    permissionsEndpoint,
    proxyGetEndpoint,
    proxyPostEndpoint,
    proxyPutEndpoint,
    proxyPatchEndpoint,
    proxyDeleteEndpoint,
  )

  val loginEndpoint =
    Method.GET / "login" / string("presetId") -> handler { (presetId: String, request: Request) =>
      for
        edgeService <- ZIO.service[EdgeService]

        response <- edgeService.authorize(PresetId(presetId))
          .either.flatMap:
            case Left(error: PresetNotFound) =>
              ZIO.succeed(Response.notFound)

            case Left(ex: Throwable) =>
              ZIO.fail(ex)

            case Right(url) =>
              ZIO.succeed(Response.seeOther(url))
      yield response
    }
  
  val completeEndpoint =
    Method.GET / "complete" -> handler { (request: Request) =>
      for
        edgeService <- ZIO.service[EdgeService]
        config <- ZIO.service[EdgeConfig]

        code <- request.queryZIO[Code]("code")
        state <- request.queryZIO[State]("state")

        response <- edgeService.complete(code, state)
          .either.flatMap:
            case Left(_: AuthConversationNotFound) =>
              ZIO.succeed(Response.badRequest)

            case Left(ex: Throwable) =>
              ZIO.fail(ex)

            case Right(completion) =>
              for
                redirectUrl <- ZIO.fromEither(URL.decode(completion.postLoginRedirectUri))
                now <- Clock.instant
              yield
                Response
                  .seeOther(redirectUrl)
                  .addCookie(
                    EdgeSessionCookie(
                      presetId = completion.presetId,
                      accessToken = completion.accessToken,
                      ttl = completion.cookieTtl,
                      domain = completion.cookieDomain,
                      path = completion.cookiePath,
                      now = now,
                    ),
                  )
      yield response
    }

  val permissionsEndpoint =
    Method.GET / "permissions" / "me" -> handler { (request: Request) =>
      for
        claims      <- authorize(request)
        service     <- ZIO.service[EdgeService]
        resourceIds <- request.queryZIO[List[String]]("resource")
        response    <- service.getMyPermissions(claims, resourceIds.map(ResourceId(_)))
      yield Response.json(response.toJson)
    }

  val proxyGetEndpoint = proxy(Method.GET)
  val proxyPostEndpoint = proxy(Method.POST)
  val proxyPutEndpoint = proxy(Method.PUT)
  val proxyPatchEndpoint = proxy(Method.PATCH)
  val proxyDeleteEndpoint = proxy(Method.DELETE)

  private def proxy(method: Method): Route[EdgeService, Throwable] =
    method / "resources" / string("resourceId") / trailing -> handler {
      (resourceId: String, rest: Path, request: Request) =>
        ZIO.serviceWithZIO[EdgeService](_.proxy(ResourceId(resourceId), rest, request))
    }
