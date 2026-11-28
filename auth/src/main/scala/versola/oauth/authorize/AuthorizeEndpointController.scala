package versola.oauth.authorize

import versola.audit.AuditSupport.*
import versola.audit.{AuditSupport, AuthActors, AuthAuditDetails, AuthAuditKinds, ClientAuthFailures}
import versola.oauth.AuthMetrics
import versola.oauth.authorize.model.{AuthorizeErrorResponse, AuthorizeRequest, AuthorizeResponse, Error, ResponseTypeEntry}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.clientauth.ClientAssertionRepository
import versola.oauth.conversation.model.AuthId
import versola.oauth.conversation.{ConversationController, ConversationRenderService, ConversationRouter}
import versola.oauth.handoff.CellHandoff
import versola.oauth.model.{AuthorizationCode, ConversationCookie}
import versola.oauth.session.model.SupersededSession
import versola.util.CoreConfig
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome, AuditUnavailableException}
import versola.util.http.{Controller, Observability}
import zio.*
import zio.http.*
import zio.json.*
import zio.prelude.NonEmptySet
import zio.telemetry.opentelemetry.tracing.Tracing

import java.time.Instant

object AuthorizeEndpointController extends Controller:
  type Env = Tracing & AuthorizeRequestParser & AuthorizeEndpointService & AuthorizationResponseService &
    OAuthConfigurationService & CoreConfig & AuditEvents & ClientAuthFailures & CellHandoff & ClientAssertionRepository &
    ConversationRouter & ConversationRenderService

  def routes: Routes[Env, Throwable] = Routes(
    getAuthorizeRoute,
    postAuthorizeRoute,
    handoffRoute,
    handedSubmissionRoute,
    registeredRoute,
  )

  /** A sign-in another cell took and sealed for this one, because this cell holds the user of the address typed there
    * (`CellHandoff`, versolauth/versola#1046). The parameters inside are validated as those of any authorization
    * request and the request starts as at `/authorize`, with the address as the login hint. A seal that is not this
    * cell's to open is a plain `400`: there is no client to tell, the browser arrived with nothing else. */
  val handoffRoute: Route[Env, Throwable] =
    Method.GET / "challenge" / "handoff" -> handler { (request: Request) =>
      ZIO.serviceWithZIO[CellHandoff](_.open(request.url.queryParams.getAll(CellHandoff.Parameter).headOption.getOrElse(""))).foldZIO(
        handoffRefused,
        payload =>
          payload.submission match
            // Made to be followed by the submission it was sealed with; a GET has none.
            case Some(_) => handoffRefused(CellHandoff.Refused.Submission)
            case None =>
              answered:
                ZIO.serviceWithZIO[AuthorizeRequestParser](_.validate(payload.params.view.mapValues(Chunk.fromIterable).toMap, request))
                  .flatMap: authorizeRequest =>
                    authorizeAnd(authorizeRequest, par = false, handed = true): (authId, started) =>
                      superseding(authId, payload).as(started),
      )
    }

  /** The session the browser had in the first cell, written on the conversation started for the sign-in handed here. */
  private def superseding(authId: AuthId, payload: CellHandoff.Payload): ZIO[ConversationRouter, Throwable, Unit] =
    superseding(authId, payload.superseded)

  private def superseding(authId: AuthId, superseded: Option[SupersededSession]): ZIO[ConversationRouter, Throwable, Unit] =
    ZIO.foreachDiscard(superseded)(session => ZIO.serviceWithZIO[ConversationRouter](_.supersedes(authId, session)))

  private def handoffRefused(refused: CellHandoff.Refused): UIO[Response] =
    AuthMetrics.authorizeError("handoff_refused") *>
      Observability.setError("handoff_refused", Some(refused.reason)).as(Response.badRequest)

  /** What a seal's id is recorded under in the store of client assertions, which is a set of values taken once until they
    * expire and nothing more. No client id has a space, so no assertion of a client shares the name. */
  private val HandedSubmissions = "cell handoff"

  /** A login and password, or a passkey with no address typed, that another cell took for a user this cell holds
    * (`CellHandoff`, versolauth/versola#1048): the first cell answered the POST with a `307` here, and this is the
    * browser repeating it. The request starts as at `/authorize` and the submission is checked in the conversation it
    * starts, so the person is not asked twice.
    *
    * The seal is what stands in for the CSRF token of a conversation this cell never had. It is refused unless the
    * browser presents the conversation cookie it was sealed with (the first cell's, which a page of another site cannot
    * put there), and it is taken once, so a passkey assertion that was captured is not good a second time.
    */
  val handedSubmissionRoute: Route[Env, Throwable] =
    Method.POST / "challenge" / "handoff" -> handler { (request: Request) =>
      val opened =
        for
          payload <- ZIO.serviceWithZIO[CellHandoff](_.open(request.url.queryParams.getAll(CellHandoff.Parameter).headOption.getOrElse("")))
          submission <- ZIO.fromOption(payload.submission).orElseFail(CellHandoff.Refused.NotASubmission)
          _ <- ZIO.fail(CellHandoff.Refused.AnotherBrowser)
            .unless(request.cookie(ConversationCookie.name).exists(cookie => CellHandoff.sameCookie(submission, cookie.content)))
        yield (payload, submission)
      opened.foldZIO(
        handoffRefused,
        (payload, submission) =>
          answered:
            ZIO.serviceWithZIO[AuthorizeRequestParser](_.validate(payload.params.view.mapValues(Chunk.fromIterable).toMap, request))
              .flatMap: authorizeRequest =>
                // Taken once the request is known to be one this cell would start, and before anything is started for it.
                ZIO.serviceWithZIO[ClientAssertionRepository](
                  _.recordIfAbsent(clientId = HandedSubmissions, jti = submission.id, expiresAt = Instant.ofEpochSecond(payload.expiresAt)),
                ).flatMap:
                  case false => handoffRefused(CellHandoff.Refused.Replayed)
                  case true =>
                    authorizeAnd(authorizeRequest, par = false): (authId, started) =>
                      (superseding(authId, payload) *>
                        ConversationController.handed(request, authId, authorizeRequest.clientId, submission.passkeyRequest))
                        .map(response => withStartedCookie(response, started)),
      )
    }

  /** What a registration's seal is recorded under in the store of client assertions, apart from the submissions'. */
  private val HandedRegistrations = "cell registration"

  /** A registration another cell verified and sealed for this one, which takes users (`CellHandoff.sealRegistration`,
    * versolauth/versola#1052): the browser arrives by the redirect of the cell where the code was typed. The seal is refused
    * unless it is this cell's to open, for a registration, unexpired, and the browser presents the conversation cookie it was
    * sealed with; it is taken once. The authorization request inside is validated as any other and starts a conversation, in
    * which the credential step is already passed and the user is registered by the ordinary path
    * (`ConversationRouter.submitRegistered`).
    */
  val registeredRoute: Route[Env, Throwable] =
    Method.GET / "challenge" / "registration" -> handler { (request: Request) =>
      val opened =
        for
          payload <- ZIO.serviceWithZIO[CellHandoff](
            _.openRegistration(request.url.queryParams.getAll(CellHandoff.RegistrationParameter).headOption.getOrElse("")),
          )
          _ <- ZIO.fail(CellHandoff.Refused.AnotherBrowser)
            .unless(request.cookie(ConversationCookie.name).exists(cookie => CellHandoff.sameCookie(payload.cookie, cookie.content)))
          registered <- ZIO.fromOption(payload.registered).orElseFail(CellHandoff.Refused.Forged)
        yield (payload, registered)
      opened.foldZIO(
        handoffRefused,
        (payload, registered) =>
          answered:
            ZIO.serviceWithZIO[AuthorizeRequestParser](_.validate(payload.params.view.mapValues(Chunk.fromIterable).toMap, request))
              .flatMap: authorizeRequest =>
                ZIO.serviceWithZIO[ClientAssertionRepository](
                  _.recordIfAbsent(clientId = HandedRegistrations, jti = payload.id, expiresAt = Instant.ofEpochSecond(payload.expiresAt)),
                ).flatMap:
                  case false => handoffRefused(CellHandoff.Refused.Replayed)
                  case true =>
                    authorizeAnd(authorizeRequest, par = false, handed = true): (authId, started) =>
                      (superseding(authId, payload.superseded) *>
                        ConversationController.registered(request, authId, authorizeRequest.clientId, registered))
                        .map(response => withStartedCookie(response, started)),
      )
    }

  /** The cookie of the conversation this cell started, unless the answer already ends it. */
  private def withStartedCookie(response: Response, started: Response): Response =
    val ended = response.headers(Header.SetCookie).exists(_.value.name == ConversationCookie.name)
    if ended then response else started.headers(Header.SetCookie).foldLeft(response)((r, cookie) => r.addHeader(cookie))

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
    authorizeAnd(request, par)((_, started) => ZIO.succeed(started))

  /** @param handed the request was handed here by another cell, which has chosen where the address goes
    * @param onStarted what answers a request that started a conversation, given its id and the redirect to its first
    *   step, which carries its cookie
    */
  private def authorizeAnd[R](request: AuthorizeRequest, par: Boolean, handed: Boolean = false)(
      onStarted: (AuthId, Response) => ZIO[R, Throwable, Response],
  ) =
    for
      authService <- ZIO.service[AuthorizeEndpointService]
      configService <- ZIO.service[OAuthConfigurationService]
      responseService <- ZIO.service[AuthorizationResponseService]
      config <- ZIO.service[CoreConfig]
      authConversationTtl <- configService.getAuthConversationTtl(request.clientId)
      started = if handed then authService.authorizeHanded(request) else authService.authorize(request)
      response <- started.tap(AuthMetrics.authorizeOutcome).tap(_ => auditSuccess(request, par)).flatMap:
        case AuthorizeResponse.Authorized(code, idToken) =>
          responseService.redirect(
            request.clientId,
            request.redirectUri,
            request.responseMode,
            AuthorizeRedirect.successParams(AuthorizationCode.present(code), request.state, idToken, config.jwt.issuer),
          ).map(Response.seeOther)

        // No cookie: the conversation this cell started is over, the home cell starts its own.
        case AuthorizeResponse.HandedOff(location) =>
          ZIO.succeed(Response.seeOther(location))

        case AuthorizeResponse.Initialize(authId) =>
          onStarted(
            authId,
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
