package versola.oauth.authorize

import versola.audit.AuditSupport.*
import versola.audit.{AuditSupport, AuthActors, AuthAuditDetails, AuthAuditKinds, ClientAuthFailures}
import versola.oauth.AuthMetrics
import versola.oauth.authorize.model.{AuthorizeErrorResponse, AuthorizeRequest, AuthorizeResponse, Error, ResponseTypeEntry}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.handoff.CellHandoff
import versola.oauth.model.{AuthorizationCode, ConversationCookie}
import versola.util.CoreConfig
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome, AuditUnavailableException}
import versola.util.http.{Controller, Observability}
import zio.*
import zio.http.*
import zio.json.*
import zio.prelude.NonEmptySet
import zio.telemetry.opentelemetry.tracing.Tracing

object AuthorizeEndpointController extends Controller:
  type Env = Tracing & AuthorizeRequestParser & AuthorizeEndpointService & AuthorizationResponseService &
    OAuthConfigurationService & CoreConfig & AuditEvents & ClientAuthFailures & CellHandoff

  def routes: Routes[Env, Throwable] = Routes(
    getAuthorizeRoute,
    postAuthorizeRoute,
    handoffRoute,
  )

  /** A sign-in another cell took and sealed for this one, because this cell holds the user of the address typed there
    * (`CellHandoff`, versolauth/versola#1046). The parameters inside are validated as those of any authorization
    * request and the request starts as at `/authorize`, with the address as the login hint. A seal that is not this
    * cell's to open is a plain `400`: there is no client to tell, the browser arrived with nothing else. */
  val handoffRoute: Route[Env, Throwable] =
    Method.GET / "challenge" / "handoff" -> handler { (request: Request) =>
      ZIO.serviceWithZIO[CellHandoff](_.open(request.url.queryParams.getAll(CellHandoff.Parameter).headOption.getOrElse(""))).foldZIO(
        refused =>
          AuthMetrics.authorizeError("handoff_refused") *>
            Observability.setError("handoff_refused", Some(refused.reason)).as(Response.badRequest),
        payload =>
          answered:
            ZIO.serviceWithZIO[AuthorizeRequestParser](_.validate(payload.params.view.mapValues(Chunk.fromIterable).toMap, request))
              .flatMap(authorizeAndRedirect(_, par = false)),
      )
    }

  val getAuthorizeRoute = authorize(Method.GET)
  val postAuthorizeRoute = authorize(Method.POST)

  def authorize(method: Method): Route[Env, Throwable] =
    method / "authorize" -> handler { (request: Request) =>
      answered:
        for
          parser <- ZIO.service[AuthorizeRequestParser]
          parsedRequest <- parser.parse(request)
          response <- authorizeAndRedirect(parsedRequest, pushed(request))
        yield response
    }

  /** The answer to an authorization request that failed: a `400` where there is no client to tell, the client's redirect
    * URI with the error where there is. */
  private def answered[R](result: ZIO[R, Throwable | Error, Response]) =
    result
      .catchSome {
        case Error.BadRequest =>
          AuthMetrics.authorizeError("invalid_request") *>
            auditFailure("invalid_request", None) *>
            (Observability.setError("invalid_request", Some(Error.BadRequest.description))
              .as(Response.badRequest(Error.BadRequest.description)))

        case error: Error.InvalidRequestObject =>
          // RFC 9101 §6.2: an object that fails verification must be reported as
          // `invalid_request_object`, not as an undifferentiated 400 -- that is the only
          // thing that lets a client tell this apart from Error.BadRequest above.
          AuthMetrics.authorizeError(Error.InvalidRequestObject.error) *>
            auditFailure(error.error, None) *>
            (Observability.setError(error.error, error.logDescription)
              .as(Response.json(
                AuthorizeErrorResponse(Error.InvalidRequestObject.error, Error.InvalidRequestObject.description).toJson,
              ).status(Status.BadRequest)))

        case error: Error.RedirectError =>
          for
            config <- ZIO.service[CoreConfig]
            responseService <- ZIO.service[AuthorizationResponseService]
            _ <- AuthMetrics.authorizeError(error.error.toString)
            _ <- Observability.setError(error.error, Some(error.errorDescription))
            _ <- auditFailure(error.error.toString, Some(error.clientId))
            redirect <- responseService.redirect(
              error.clientId,
              error.uri,
              error.responseMode,
              error.errorParams(config.jwt.issuer),
            )
          yield Response.seeOther(redirect)
      }

  /** RFC 9126 §4: a pushed request is redeemed at `/authorize` by its `request_uri`. */
  private def pushed(request: Request): Boolean = request.url.queryParams.hasQueryParam("request_uri")

  /** `authorize.request`, failed: the request never became a conversation. Anyone can send one, so it is counted per client, address and
    * reason and written once a minute with `count` (see `ClientAuthFailures`). What the request asked for is not known when it did not
    * parse, so the details name only whether it was pushed. */
  private def auditFailure(reason: String, clientId: Option[String]): ZIO[ClientAuthFailures, Nothing, Unit] =
    ZIO.foreachDiscard(clientId)(Observability.setClientId) *>
      ZIO.serviceWithZIO[ClientAuthFailures](
        _.refused(
          kind = AuthAuditKinds.AuthorizeRequest,
          outcome = AuditOutcome.failure,
          clientId = clientId,
          reason = reason,
          details = AuthAuditDetails.AuthorizeRequest(
            responseType = Nil,
            scope = Nil,
            resource = Nil,
            prompt = Nil,
            acrValues = Nil,
            par = false,
            count = None,
          ),
        ),
      )

  /** `authorize.request`, succeeded: a conversation was started or a code issued silently. It is every sign-in's first line. */
  private def auditSuccess(request: AuthorizeRequest, par: Boolean): ZIO[AuditEvents, AuditUnavailableException.type, Unit] =
    ZIO.serviceWithZIO[AuditEvents](
      _.log(
        AuditDraft(
          kind = AuthAuditKinds.AuthorizeRequest,
          outcome = AuditOutcome.success,
          reason = None,
          actor = Some((AuthActors.client, Some(request.clientId))),
          userId = None,
          target = None,
          details = AuthAuditDetails.AuthorizeRequest(
            responseType = request.responseType.toList.map(_.toString).sorted,
            scope = request.scope.toList.map(_.toString).sorted,
            resource = request.resources.map(_.toString),
            prompt = request.prompt.toList.map(_.toString).sorted,
            acrValues = request.acrValues.toList.flatMap(_.toList).map(_.toString),
            par = par,
            count = None,
          ),
        ),
      ),
    ).unit

  private def authorizeAndRedirect(request: AuthorizeRequest, par: Boolean) =
    for
      authService <- ZIO.service[AuthorizeEndpointService]
      configService <- ZIO.service[OAuthConfigurationService]
      responseService <- ZIO.service[AuthorizationResponseService]
      config <- ZIO.service[CoreConfig]
      authConversationTtl <- configService.getAuthConversationTtl(request.clientId)
      response <- authService.authorize(request).tap(AuthMetrics.authorizeOutcome).tap(_ => auditSuccess(request, par)).flatMap:
        case AuthorizeResponse.Authorized(code, idToken) =>
          responseService.redirect(
            request.clientId,
            request.redirectUri,
            request.responseMode,
            AuthorizeRedirect.successParams(AuthorizationCode.present(code), request.state, idToken, config.jwt.issuer),
          ).map(Response.seeOther)

        case AuthorizeResponse.Initialize(authId) =>
          ZIO.succeed(
            Response.seeOther(URL.root / "challenge")
              .addCookie(
                ConversationCookie.responseCookie(
                  ConversationCookie(
                    authId,
                    request.clientId,
                    redirectUri = request.redirectUri.encode,
                    state = request.state,
                    responseMode = Some(request.responseMode),
                    useFragment = None,
                  ),
                  authConversationTtl,
                  config.security.conversationCookieSecret,
                  config.cellId,
                ),
              ),
          )
    yield response
