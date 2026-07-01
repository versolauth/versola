package versola.edge

import versola.edge.model.{AuthConversationNotFound, Code, PresetId, PresetNotFound, State}
import versola.util.http.Controller
import zio.*
import zio.http.*

object EdgeController extends Controller:
  type Env = Tracing & EdgeService & EdgeConfig

  def routes: Routes[Env, Throwable] = Routes(
    loginEndpoint,
    completeEndpoint,
    proxyGetEndpoint,
    proxyPostEndpoint,
    proxyPutEndpoint,
    proxyPatchEndpoint,
    proxyDeleteEndpoint,
  )

  val loginEndpoint =
    Method.GET / "login" -> handler { (request: Request) =>
      for
        edgeService <- ZIO.service[EdgeService]

        presetId <- request.queryZIO[PresetId]("pid")

        response <- edgeService.authorize(presetId)
          .either.flatMap:
            case Left(error: PresetNotFound) =>
              ZIO.succeed(Response.badRequest)

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
                      accessToken = completion.accessToken,
                      ttl = completion.cookieTtl,
                      domain = completion.cookieDomain,
                      path = completion.cookiePath,
                      now = now,
                    ),
                  )
      yield response
    }

  val proxyGetEndpoint =
    Method.GET / "resources" / string("alias") / trailing -> handler(proxy(_, _, _))

  val proxyPostEndpoint =
    Method.POST / "resources" / string("alias") / trailing -> handler(proxy(_, _, _))

  val proxyPutEndpoint =
    Method.PUT / "resources" / string("alias") / trailing -> handler(proxy(_, _, _))

  val proxyPatchEndpoint =
    Method.PATCH / "resources" / string("alias") / trailing -> handler(proxy(_, _, _))

  val proxyDeleteEndpoint =
    Method.DELETE / "resources" / string("alias") / trailing -> handler(proxy(_, _, _))

  private def proxy(
      alias: String,
      rest: Path,
      request: Request,
  ): ZIO[EdgeService, Throwable, Response] =
    ZIO.serviceWithZIO[EdgeService](_.proxy(alias, rest, request))
