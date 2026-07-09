package versola.edge

import versola.edge.model.{AuthConversationNotFound, Code, PresetId, PresetNotFound, ResourceId, State}
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.json.EncoderOps

object EdgeController extends Controller:
  type Env = Tracing & EdgeService & EdgeConfig & JwksService & Client

  def routes: Routes[Env, Throwable] = Routes(
    loginEndpoint,
    completeEndpoint,
    authSettingsGetRoute,
    authSettingsLogoutRoute,
    authSettingsDeletePasskeyRoute,
    authSettingsRegisterPasskeyRoute,
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

  val authSettingsGetRoute =
    Method.GET / "auth-settings" -> handler { (request: Request) =>
      for
        config   <- ZIO.service[EdgeConfig]
        client   <- ZIO.service[Client]
        response <- request.cookie(EdgeSessionCookie.name).fold(
                      ZIO.succeed(Response.unauthorized)
                    ): cookie =>
                      val (_, accessToken) = EdgeSessionCookie.parse(cookie.content)
                      val authReq = Request
                        .get(config.versolaUrl / "auth-settings")
                        .addHeader(Header.Authorization.Bearer(accessToken.toString))
                      ZIO.scoped:
                        for
                          r       <- client.request(authReq)
                          resBody <- r.body.asChunk
                        yield Response(
                          r.status,
                          r.headers
                            .removeHeader(Header.TransferEncoding)
                            .removeHeader(Header.ContentEncoding)
                            .removeHeader(Header.ContentLength)
                            .removeHeader(Header.Connection)
                            .removeHeader("Keep-Alive"),
                          Body.fromChunk(resBody),
                        )
      yield response
    }

  val authSettingsLogoutRoute =
    Method.POST / "auth-settings" / "sessions" / "logout" -> handler { (request: Request) =>
      proxyPostToAuth(_ / "auth-settings" / "sessions" / "logout", request)
    }

  val authSettingsDeletePasskeyRoute =
    Method.POST / "auth-settings" / "passkeys" / "delete" -> handler { (request: Request) =>
      proxyPostToAuth(_ / "auth-settings" / "passkeys" / "delete", request)
    }

  val authSettingsRegisterPasskeyRoute =
    Method.POST / "auth-settings" / "passkeys" / "register" -> handler { (request: Request) =>
      proxyPostToAuth(_ / "auth-settings" / "passkeys" / "register", request)
    }

  private def proxyPostToAuth(
      path: URL => URL,
      request: Request,
  ): ZIO[EdgeConfig & Client, Throwable, Response] =
    for
      config  <- ZIO.service[EdgeConfig]
      client  <- ZIO.service[Client]
      reqBody <- request.body.asChunk
      forwardedCookies = request.cookies
        .filter(c => c.name == "SSO_ACCOUNT" || c.name == "SSO_PASSKEY_REG")
        .map(_.toRequest)
      baseHeaders = request.headers
        .removeHeader(Header.Cookie)
        .removeHeader(Header.Host)
        .removeHeader(Header.Authorization)
      headersWithCookies = NonEmptyChunk.fromChunk(forwardedCookies) match
        case Some(cookies) => baseHeaders.addHeader(Header.Cookie(cookies))
        case None          => baseHeaders
      authReq  = Request(
                   method  = Method.POST,
                   url     = path(config.versolaUrl),
                   headers = headersWithCookies,
                   body    = Body.fromChunk(reqBody),
                 )
      response <- ZIO.scoped:
                    for
                      r       <- client.request(authReq)
                      resBody <- r.body.asChunk
                    yield Response(
                      r.status,
                      r.headers
                        .removeHeader(Header.TransferEncoding)
                        .removeHeader(Header.ContentEncoding)
                        .removeHeader(Header.ContentLength)
                        .removeHeader(Header.Connection)
                        .removeHeader("Keep-Alive"),
                      Body.fromChunk(resBody),
                    )
    yield response

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
