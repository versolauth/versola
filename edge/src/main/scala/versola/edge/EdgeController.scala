package versola.edge

import versola.edge.model.{AuthConversationNotFound, Code, InvalidLogoutToken, PresetId, PresetNotFound, ResourceId, SessionId, State}
import versola.edge.revocation.TokenRevocationService
import versola.util.FormDecoder
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.json.{EncoderOps, JsonEncoder, jsonField}

object EdgeController extends Controller:
  type Env = Tracing & EdgeService & EdgeConfig & JwksService & TokenRevocationService & AuthorizationPresetsSyncClient

  /** OIDC Back-Channel Logout §2.8 error response body. */
  private case class LogoutError(
      error: String,
      @jsonField("error_description") errorDescription: String,
  ) derives JsonEncoder

  def routes: Routes[Env, Throwable] = Routes(
    loginEndpoint,
    logoutEndpoint,
    completeEndpoint,
    frontChannelLogoutEndpoint,
    backChannelLogoutEndpoint,
    permissionsEndpoint,
    proxyGetEndpoint,
    proxyPostEndpoint,
    proxyPutEndpoint,
    proxyPatchEndpoint,
    proxyDeleteEndpoint,
  )

  private val loginParamWhitelist = Set(
    "acr_values",
    "max_age",
    "prompt",
    "login_hint",
    "ui_locales",
  )

  val loginEndpoint =
    Method.GET / "login" / string("presetId") -> handler { (presetId: String, request: Request) =>
      for
        edgeService <- ZIO.service[EdgeService]
        overrideParams: Map[String, String] = loginParamWhitelist.flatMap { key =>
          request.url.queryParams.map.get(key).flatMap(_.headOption).map(key -> _)
        }.toMap

        response <- edgeService.authorize(PresetId(presetId), overrideParams)
          .either.flatMap:
            case Left(error: PresetNotFound) =>
              ZIO.succeed(Response.notFound)

            case Left(ex: Throwable) =>
              ZIO.fail(ex)

            case Right(url) =>
              ZIO.succeed(Response.seeOther(url))
      yield response
    }

  val logoutEndpoint: Route[EdgeConfig & AuthorizationPresetsSyncClient, Throwable] =
    Method.GET / "logout" / string("presetId") -> handler { (presetId: String, _: Request) =>
      for
        config <- ZIO.service[EdgeConfig]
        presets <- ZIO.service[AuthorizationPresetsSyncClient]
        preset <- presets.getAll.map(_.get(PresetId(presetId))).someOrFail(PresetNotFound).mapError {
          case error: Throwable => error
          case _ => RuntimeException("Preset not found")
        }
        redirect <- ZIO.fromEither(URL.decode(config.versolaUrl.toString + "/logout"))
        target = preset.postLogoutRedirectUri.fold(redirect)(uri => redirect.addQueryParam("post_logout_redirect_uri", uri.toString))
      yield Response.seeOther(target)
    }

  val completeEndpoint =
    Method.GET / "complete" -> handler { (request: Request) =>
      for
        edgeService <- ZIO.service[EdgeService]
        code <- request.queryZIO[Option[Code]]("code")
        state <- request.queryZIO[State]("state")
        error <- request.queryZIO[Option[String]]("error")
        errorDescription <- request.queryZIO[Option[String]]("error_description")
        errorUri <- request.queryZIO[Option[String]]("error_uri")

        response <- (code, error) match
          case (Some(code), None) =>
            edgeService.complete(code, state)
              .either.flatMap:
                case Left(_: AuthConversationNotFound) =>
                  ZIO.succeed(Response.badRequest)

                case Left(ex: Throwable) =>
                  ZIO.fail(ex)

                case Right(completion) =>
                  for
                    now <- Clock.instant
                  yield Response
                    .seeOther(completion.postLoginRedirectUri.toUrl)
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

          case (None, Some(error)) =>
            edgeService.completeError(state, error, errorDescription, errorUri)
              .either.flatMap:
                case Left(_: AuthConversationNotFound) =>
                  ZIO.succeed(Response.badRequest)

                case Left(ex: Throwable) =>
                  ZIO.fail(ex)

                case Right(redirectUrl) =>
                  ZIO.succeed(Response.seeOther(redirectUrl))

          case _ =>
            ZIO.succeed(Response.badRequest)
      yield response
    }

  // Front-channel logout, invoked either by the OP in a hidden iframe with the `iss`/`sid`
  // query params of the OIDC logout token, or first-party by the browser with no params at
  // all. EDGE_SESSION is read, not just cleared: it is what authorizes the revocation, and
  // being SameSite=Strict it only reaches here on a same-site request.
  val frontChannelLogoutEndpoint =
    Method.GET / "logout" / "frontchannel" -> handler { (request: Request) =>
      for
        edgeService <- ZIO.service[EdgeService]
        iss = request.url.queryParams.getAll("iss").headOption
        sid = request.url.queryParams.getAll("sid").headOption.map(SessionId(_))
        sessionCookie = request.cookie(EdgeSessionCookie.name).map(_.content)
        cookies <- edgeService.frontChannelLogout(iss, sid, sessionCookie)
      yield cookies.foldLeft(
        Response
          .status(Status.Ok)
          .addHeader(Header.CacheControl.NoStore),
      )(_.addCookie(_))
    }

  // OP-initiated back-channel logout, invoked by the OP directly, server-to-server, with
  // the signed `logout_token` of the OIDC logout. The browser is not involved, so no
  // cookie is cleared: dropping the session rows is what stops EDGE_SESSION from being
  // honoured. One registration covers every preset behind this edge.
  val backChannelLogoutEndpoint =
    Method.POST / "logout" / "backchannel" -> handler { (request: Request) =>
      (for
        edgeService <- ZIO.service[EdgeService]
        form <- request.body.asURLEncodedForm
        logoutToken <- FormDecoder.single(form, "logout_token", Right(_))
          .mapError(InvalidLogoutToken(_))
        _ <- edgeService.backChannelLogout(logoutToken)
      yield Response.status(Status.Ok).addHeader(Header.CacheControl.NoStore))
        .catchAll:
          case invalid: InvalidLogoutToken =>
            ZIO.succeed(
              Response
                .json(LogoutError("invalid_request", invalid.reason).toJson)
                .status(Status.BadRequest)
                .addHeader(Header.CacheControl.NoStore),
            )
          case error: Throwable => ZIO.fail(error)
    }

  val permissionsEndpoint =
    Method.GET / "permissions" / "me" -> handler { (request: Request) =>
      for
        claims <- authorize(request)
        service <- ZIO.service[EdgeService]
        resourceIds <- request.queryZIO[List[ResourceId]]("resource")
        response <- service.getMyPermissions(claims, resourceIds)
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
