package versola.oauth.account

import versola.auth.model.{CredentialId, PasskeyName}
import versola.oauth.challenge.passkey.{PasskeyRepository, WebAuthnService}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.ConversationRenderService
import versola.oauth.conversation.ConversationRenderService.StepView
import versola.oauth.session.SessionService
import versola.oauth.session.model.PublicSessionId
import versola.user.UserRepository
import versola.user.model.UserId
import versola.util.http.{BadRequest, Controller, Observability, Unauthorized}
import versola.util.{Base64, CoreConfig, Secret}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

import java.security.MessageDigest

/** The self-service Security page and APIs, exposed only on auth's additional listener.
    * Edge authenticates and authorizes the caller, performs configured step-up checks, then
    * authenticates to this resource with Basic credentials and overwrites the caller headers.
    */
object AccountSettingsController extends Controller:
  type Env = Tracing & CoreConfig & OAuthConfigurationService & SessionService &
    PasskeyRepository & WebAuthnService & UserRepository & ConversationRenderService

  /** How long an unfinished enrollment ceremony stays usable. */
  private val enrollmentTtl = 5.minutes

  def routes: Routes[Env, Throwable] = Routes(
    pageRoute,
    revokeSessionRoute,
    renamePasskeyRoute,
    deletePasskeyRoute,
    startPasskeyEnrollmentRoute,
    finishPasskeyEnrollmentRoute,
  )

  val pageRoute: Route[Env, Throwable] =
    Method.GET / "settings" -> handler { (request: Request) =>
      for
        _ <- authorizeResource(request)
        userId <- request.queryZIO[UserId]("userId")
        clientId <- request.queryZIO[ClientId]("clientId")
        sessionId <- request.queryZIO[PublicSessionId]("sessionId")
        view <- accountView(userId, sessionId)
        requestedLocales <- request.queryZIO[Option[String]]("ui_locales")
        uiLocales = Some(requestedLocales.fold(acceptedLocales(request))(splitLocales)).filter(_.nonEmpty)
        response <- ZIO.serviceWithZIO[ConversationRenderService](_.renderAccount(clientId, view, uiLocales))
      yield response
    }

  /** Revocation is scoped atomically to the caller. Knowledge of another session's id
    * is not sufficient because it travels in front-channel logout URLs and RP access logs.
    * Revoking the caller's own session is refused by edge's access rule for this endpoint
    * (see `BootstrapService.accountAllowExpression`), so this route no longer needs the
    * caller's own `sid` - only which session to invalidate. */
  val revokeSessionRoute: Route[Env, Throwable] =
    Method.DELETE / "settings" / "sessions" -> handler { (request: Request) =>
      for
        _ <- authorizeResource(request)
        body <- request.bodyAs[RevokeSessionRequest]
        response <- ZIO.serviceWithZIO[SessionService](_.invalidateForUser(body.targetSessionId, body.userId)).flatMap:
          case true => ZIO.succeed(Response.status(Status.NoContent))
          case false => Observability.setError("session_not_found").as(Response.status(Status.NoContent))
      yield response
    }

  val renamePasskeyRoute: Route[Env, Throwable] =
    Method.PATCH / "settings" / "passkeys" -> handler { (request: Request) =>
      for
        _ <- authorizeResource(request)
        body <- request.bodyAs[RenamePasskeyRequest]
        _ <- ZIO.serviceWithZIO[PasskeyRepository](_.rename(body.credentialId, body.userId, body.name))
      yield Response.status(Status.NoContent)
    }

  val deletePasskeyRoute: Route[Env, Throwable] =
    Method.DELETE / "settings" / "passkeys" -> handler { (request: Request) =>
      for
        _ <- authorizeResource(request)
        body <- request.bodyAs[DeletePasskeyRequest]
        _ <- ZIO.serviceWithZIO[PasskeyRepository](_.deleteByUser(body.credentialId, body.userId))
      yield Response.status(Status.NoContent)
    }

  /** Hands the browser the `navigator.credentials.create()` options plus the signed ceremony
    * ticket it must echo back on completion (see [[PasskeyEnrollmentTicket]]). */
  val startPasskeyEnrollmentRoute: Route[Env, Throwable] =
    Method.POST / "settings" / "passkeys" / "register" / "start" -> handler { (request: Request) =>
      for
        _ <- authorizeResource(request)
        body <- request.bodyAs[StartEnrollmentRequest]
        config <- ZIO.service[CoreConfig]
        settings <- ZIO.serviceWithZIO[OAuthConfigurationService](_.getPasskeySettings(body.clientId))
          .someOrFail(BadRequest("passkeys are not enabled"))
        displayName <- displayNameOf(body.userId)
        ceremony <- ZIO.serviceWithZIO[WebAuthnService](_.startRegistration(settings, body.userId, displayName))
        now <- Clock.instant
        ticket = PasskeyEnrollmentTicket.serialize(
          PasskeyEnrollmentTicket(body.userId, now.plus(enrollmentTtl), ceremony.request),
          config.security.conversationCookieSecret,
        )
      yield Response.json(StartEnrollmentResponse(ceremony.publicKeyOptions, ticket).toJson)
    }

  val finishPasskeyEnrollmentRoute: Route[Env, Throwable] =
    Method.POST / "settings" / "passkeys" / "register" / "finish" -> handler { (request: Request) =>
      for
        _ <- authorizeResource(request)
        body <- request.bodyAs[FinishEnrollmentRequest]
        ticket <- parseAndValidatePasskeyTicket(body)
        record <- ZIO.serviceWithZIO[WebAuthnService](
          _.finishRegistration(body.clientId, body.userId, ticket.request, body.response.toJson, Some(body.name)),
        ).tapError(error => Observability.setError("enroll_failed", Some(error.getMessage)))
          .mapError(error => BadRequest(error.getMessage))
      yield Response.json(passkeyView(record.id, record.name, record.backedUp, record.lastUsedAt, record.createdAt).toJson)
    }

  /** Authenticates edge's Basic credentials for this resource, whose secret is synced
    * encrypted from central rather than configured statically (see [[OAuthConfigurationService]]). */
  private def authorizeResource(request: Request): ZIO[OAuthConfigurationService, Throwable, Unit] =
    ZIO.serviceWithZIO[OAuthConfigurationService] { service =>
      service.accountResourceSecrets.flatMap:
        case Nil => ZIO.fail(Unauthorized)
        case secrets => authorizeBasic(request, secrets)
    }

  private def parseAndValidatePasskeyTicket(
      body: FinishEnrollmentRequest,
  ): ZIO[CoreConfig, Throwable, PasskeyEnrollmentTicket] =
    for
      config <- ZIO.service[CoreConfig]
      now <- Clock.instant
      ticket <- ZIO.fromEither(PasskeyEnrollmentTicket.parse(body.ticket, config.security.conversationCookieSecret, now))
        .tapError(reason => Observability.setError("invalid_ceremony", Some(reason)))
        .mapError(BadRequest(_))
      // The ticket is authenticated but not bound to a session: reject one minted for
      // another user rather than enrolling their credential under this caller.
      _ <- ZIO.fail(Unauthorized).unless(ticket.userId == body.userId)
    yield ticket

  /** Accepts any secret central currently reports for this resource: during a rotation that
    * is both the new one and the one being rotated out, since edge switches over only when
    * the previous secret is removed and the two services refresh their caches independently. */
  private def authorizeBasic(request: Request, expectedSecrets: List[Secret]): IO[Unauthorized.type, Unit] =
    request.header(Header.Authorization) match
      case Some(Header.Authorization.Basic(username, password)) if username == "auth" =>
        for
          provided <- ZIO.fromEither(Secret.fromBase64Url(password.stringValue)).orElseFail(Unauthorized)
          _ <- ZIO.fail(Unauthorized).unless(expectedSecrets.exists(MessageDigest.isEqual(provided, _)))
        yield ()
      case _ => ZIO.fail(Unauthorized)

  private def accountView(
      userId: UserId,
      sessionId: PublicSessionId,
  ): ZIO[SessionService & PasskeyRepository, Throwable, StepView.AccountSettings] =
    for
      sessions <- listSessions(userId, sessionId)
      passkeys <- listPasskeys(userId)
    yield StepView.AccountSettings(sessions, passkeys)

  private def listSessions(userId: UserId, sessionId: PublicSessionId): ZIO[SessionService, Throwable, List[StepView.AccountSession]] =
    ZIO.serviceWithZIO[SessionService](_.listByUser(userId)).map:
      _.map: session =>
        StepView.AccountSession(
          id = session.publicId,
          platform = session.platform,
          os = session.os,
          browser = session.browser,
          version = session.version,
          createdAt = session.createdAt,
          expiresAt = session.expiresAt,
          current = sessionId == session.publicId,
        )

  private def listPasskeys(userId: UserId): ZIO[PasskeyRepository, Throwable, List[StepView.AccountPasskey]] =
    ZIO.serviceWithZIO[PasskeyRepository](_.listByUser(userId)).map:
      _.map(record => passkeyView(record.id, record.name, record.backedUp, record.lastUsedAt, record.createdAt)).toList

  private def passkeyView(
      id: CredentialId,
      name: Option[PasskeyName],
      backedUp: Boolean,
      lastUsedAt: Option[java.time.Instant],
      createdAt: java.time.Instant,
  ): StepView.AccountPasskey =
    StepView.AccountPasskey(
      id = Base64.urlEncode(id),
      name = name,
      backedUp = backedUp,
      lastUsedAt = lastUsedAt,
      createdAt = createdAt,
    )

  /** The name the authenticator shows in its account picker, resolved the same way the
    * enrollment step of the login flow resolves it. */
  private def displayNameOf(userId: UserId): ZIO[UserRepository, Throwable, String] =
    ZIO.serviceWithZIO[UserRepository](_.find(userId)).map(_.map(_.passkeyDisplayName).getOrElse(userId.toString))

  private def acceptedLocales(request: Request): List[String] =
    request.header(Header.AcceptLanguage).toList.flatMap(_.renderedValue.split(',').toList)
      .map(_.takeWhile(_ != ';').trim)
      .filter(_.nonEmpty)

  /** Parses the OIDC `ui_locales` convention: a space-separated, most-preferred-first list
    * (see [[versola.oauth.authorize.AuthorizeRequestParser]]). */
  private def splitLocales(raw: String): List[String] =
    raw.split(' ').toList.map(_.trim).filter(_.nonEmpty)

  /** `userId` is the edge-injected caller context (needed to scope the revocation to the
    * caller's own account). `targetSessionId` is the id of the session to invalidate,
    * chosen by the caller in the UI. */
  private case class RevokeSessionRequest(
      userId: UserId,
      targetSessionId: PublicSessionId,
  ) derives JsonCodec

  private case class RenamePasskeyRequest(
      userId: UserId,
      credentialId: CredentialId,
      name: Option[PasskeyName],
  ) derives JsonCodec

  private case class DeletePasskeyRequest(
      userId: UserId,
      credentialId: CredentialId,
  ) derives JsonCodec

  /** Carries only the caller context: the ceremony itself is derived from the client's
    * passkey settings, so the browser sends an otherwise empty JSON object. */
  private case class StartEnrollmentRequest(
      userId: UserId,
      clientId: ClientId,
  ) derives JsonCodec

  private case class StartEnrollmentResponse(publicKeyOptions: String, ticket: String) derives JsonCodec

  /** `response` is the authenticator's registration response, kept as raw JSON: the WebAuthn
    * library parses and verifies it, so nothing here interprets its shape. */
  private case class FinishEnrollmentRequest(
      userId: UserId,
      clientId: ClientId,
      ticket: String,
      response: Json,
      name: PasskeyName,
  ) derives JsonCodec
