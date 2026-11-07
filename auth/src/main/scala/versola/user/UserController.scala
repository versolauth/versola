package versola.user

import versola.audit.AuditSupport.*
import versola.audit.AuthAuditDetails
import versola.audit.AuthAuditKinds
import versola.audit.{AuditCursor, AuditReportError, AuditReportService, AuthActors}
import versola.auth.model.CredentialId
import versola.auth.model.Password
import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.{PasswordChangeEffect, PasswordReuseError}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, TenantId}
import versola.oauth.conversation.limit.ChallengeThrottleRepository
import versola.oauth.logout.LogoutService
import versola.oauth.revocation.{RevocationCause, RevocationTerms}
import versola.oauth.revoke.AccessTokenRevocationService
import versola.oauth.session.SessionService
import versola.oauth.session.model.{PublicSessionId, SessionId, SessionUnderUserAgent, UserAgentId, UserAgentRecord}
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.CoreConfig
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.http.{Controller, Observability}
import versola.util.{Email, PageLimit, Phone}
import zio.*
import zio.http.{Header, Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps
import zio.json.JsonCodec
import zio.telemetry.opentelemetry.tracing.Tracing

import java.time.Instant

object UserController extends Controller:
  type Env =
    Tracing & UserRepository & CoreConfig & LogoutService & SessionService & ChallengeThrottleRepository & PasskeyRepository & PasswordService &
      AuditEvents & AuditReportService & OAuthConfigurationService & AccessTokenRevocationService

  /** What an administrator did to an account, as central told auth: the actor is the administrator the call names (`actorId`) or the
    * `central` service, as noted in the request's context, the subject the user. `actor` says it is the service whatever the call names:
    * what auth does as the effect of a decision central recorded (the account removed, its roles changed). */
  private def audited(
      kind: versola.util.audit.AuditKind,
      userId: UserId,
      details: versola.util.audit.AuditDetails,
      actor: Option[(versola.util.audit.ActorType, Option[String])] = None,
  ): ZIO[AuditEvents, Throwable, Unit] =
    ZIO.serviceWithZIO[AuditEvents](
      _.log(
        AuditDraft(
          kind = kind,
          outcome = AuditOutcome.success,
          reason = None,
          actor = actor,
          userId = Some(userId),
          target = Some(("user", userId.toString)),
          details = details,
        ),
      ),
    )

  private val asService = Some((AuthActors.service, Some("central")))

  def routes: Routes[Env, Throwable] = Routes(
    upsertUserEndpoint,
    deleteUserEndpoint,
    patchClaimsEndpoint,
    patchRolesEndpoint,
    findClaimsEndpoint,
    findRolesEndpoint,
    findSessionsEndpoint,
    invalidateSessionEndpoint,
    findUserAgentsEndpoint,
    forgetUserAgentEndpoint,
    findRefreshTokensEndpoint,
    revokeRefreshTokensEndpoint,
    resetLimitsEndpoint,
    listPasskeysEndpoint,
    renamePasskeyEndpoint,
    deletePasskeyEndpoint,
    resetPasswordEndpoint,
    setPasswordEndpoint,
    auditEventsEndpoint,
    auditSummaryEndpoint,
  )

  val upsertUserEndpoint =
    Method.PUT / "users" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRepository]
        body <- request.body.asJsonFromCodec[UpsertUserPayload]
        // The identifiers are what takes an account over: what changed is what the account held before the write and holds after it,
        // which also covers a write that central's version ordering ignored. Names only, never the values.
        before <- repo.find(body.id)
        _ <- repo.upsert(body.id, body.version, body.email, body.phone, body.login)
        after <- repo.find(body.id)
        changed = List(
          "email" -> (before.flatMap(_.email) != after.flatMap(_.email)),
          "phone" -> (before.flatMap(_.phone) != after.flatMap(_.phone)),
          "login" -> (before.flatMap(_.login) != after.flatMap(_.login)),
        ).collect { case (name, true) => name }
        _ <- ZIO.when(changed.nonEmpty)(audited(AuthAuditKinds.AccountUpdated, body.id, AuthAuditDetails.AccountUpdated(changed), asService))
      yield Response.status(Status.NoContent)
    }

  /** Everything the account can still act with goes in one purge, and the relying parties of
    * its sessions are told first: a back-channel logout is sent while the sessions are
    * still readable, since the purge takes them with it. Idempotent -- central's outbox may
    * deliver it again behind an upsert that was already in flight. */
  val deleteUserEndpoint =
    Method.DELETE / "users" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRepository]
        logoutService <- ZIO.service[LogoutService]
        id <- request.url.queryZIO[UserId]("id")
        _ <- Observability.setUserId(id.toString)
        _ <- logoutService.invalidateAllSessions(id, RevocationCause.UserDeleted)
        _ <- repo.delete(id)
        _ <- audited(AuthAuditKinds.AccountDeleted, id, AuthAuditDetails.Account(via = "admin", detail = None), asService)
      yield Response.status(Status.NoContent)
    }

  val patchClaimsEndpoint =
    Method.PATCH / "users" / "claims" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRepository]
        body <- request.body.asJsonFromCodec[PatchUserClaimsPayload]
        _ <- actedBy(body.actorId)
        _ <- repo.patchClaims(body.id, body.claims)
        _ <- audited(AuthAuditKinds.AccountClaimsUpdated, body.id, AuthAuditDetails.ClaimsUpdated(body.claims.fields.map(_._1).toList.sorted))
      yield Response.status(Status.NoContent)
    }

  val patchRolesEndpoint =
    Method.PATCH / "users" / "roles" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRepository]
        body <- request.body.asJsonFromCodec[UpdateUserRolesPayload]
        // A role in a token is a right, not a hint: taking one away refuses the tokens that still carry it, in the
        // transaction that removes it. Adding one revokes nothing, and the refresh token is not touched either, which is
        // how the user gets a token with the roles they hold now.
        terms <- ZIO.serviceWithZIO[OAuthConfigurationService](_.accessTokenTtls).map(RevocationTerms.widest(RevocationCause.RolesChanged, _))
        _ <- repo.updateRoles(body.userId, body.tenantId, body.add, body.remove, terms)
        _ <- audited(
          AuthAuditKinds.AccountRolesUpdated,
          body.userId,
          AuthAuditDetails.RolesUpdated(
            tenantId = body.tenantId.toString,
            added = body.add.toList.map(_.toString).sorted,
            removed = body.remove.toList.map(_.toString).sorted,
          ),
          asService,
        )
      yield Response.status(Status.NoContent)
    }

  val findClaimsEndpoint =
    Method.GET / "users" / "claims" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRepository]
        id <- request.url.queryZIO[UserId]("id")
        user <- repo.find(id)
      yield user match
        case Some(record) => Response.json(UserClaimsResponse(record.claims).toJson)
        case None => Response.status(Status.NoContent)
    }

  /** The per-user report of the audit log, for central's report of an administrator: a page of the events about the user, newest first,
    * over a window of the hot retention. Every input is a query parameter decoded to its type, so one that does not decode is a bad
    * request before the service reads anything. `{ events, nextCursor, retentionFrom }`. */
  val auditEventsEndpoint =
    Method.GET / "users" / "audit-events" -> handler { (request: Request) =>
      (for
        _ <- authorizeInternal(request)
        id <- request.url.queryZIO[UserId]("id")
        from <- request.url.queryZIO[Option[Instant]]("from")
        to <- request.url.queryZIO[Option[Instant]]("to")
        cursor <- request.url.queryZIO[Option[AuditCursor]]("cursor")
        limit <- request.url.queryZIO[Option[PageLimit]]("limit")
        service <- ZIO.service[AuditReportService]
        page <- service.events(id, from, to, cursor, limit)
      yield Response.json(page.toJson)).catchAll(refuseReport)
    }

  /** The summary of the same events: `{ window, byKind, byClient, ips, userAgents, sessions, countries, factors, high }`. */
  val auditSummaryEndpoint =
    Method.GET / "users" / "audit-summary" -> handler { (request: Request) =>
      (for
        _ <- authorizeInternal(request)
        id <- request.url.queryZIO[UserId]("id")
        from <- request.url.queryZIO[Option[Instant]]("from")
        to <- request.url.queryZIO[Option[Instant]]("to")
        service <- ZIO.service[AuditReportService]
        report <- service.summary(id, from, to)
      yield Response.json(report.toJson)).catchAll(refuseReport)
    }

  /** A window that cannot be served is `400` with the reason as text, and a report that finds no place to read is `503`; an
    * unauthenticated request, an input that does not decode and a failure of the service go on to the global handler. */
  private def refuseReport(error: Throwable | AuditReportError): ZIO[Any, Throwable, Response] =
    error match
      case AuditReportError.Busy =>
        Observability.setError(AuditReportError.Busy.code).as(
          Response.text(AuditReportError.Busy.message).status(Status.ServiceUnavailable).addHeader(Header.Custom("Retry-After", "1")),
        )
      case error: AuditReportError => Observability.setError(error.code).as(Response.text(error.message).status(Status.BadRequest))
      case error: Throwable => ZIO.fail(error)

  val findRolesEndpoint =
    Method.GET / "users" / "roles" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRepository]
        id <- request.url.queryZIO[UserId]("id")
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        roles <- repo.findRolesByUserAndTenant(id, tenantId)
      yield Response.json(UserRolesResponse(roles).toJson)
    }

  val findSessionsEndpoint =
    Method.GET / "users" / "sessions" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        sessionService <- ZIO.service[SessionService]
        userId <- request.url.queryZIO[UserId]("id")
        sessions <- sessionService.listByUser(userId)
        responses = sessions.map(sessionResponse)
      yield Response.json(SessionListResponse(responses).toJson)
    }

  private def userAgentResponse(agent: UserAgentRecord): UserAgentResponse =
    UserAgentResponse(
      id = agent.id.toString,
      userAgent = agent.userAgent,
      platform = agent.platform,
      os = agent.os,
      browser = agent.browser,
      version = agent.version,
      createdAt = agent.createdAt,
      expiresAt = agent.expiresAt,
    )

  private def sessionResponse(session: SessionUnderUserAgent): SessionResponse =
    SessionResponse(
      publicId = session.publicId,
      clients = session.clients.map(c => ClientEntryResponse(c.clientId, c.enteredAt)),
      platform = session.platform,
      os = session.os,
      browser = session.browser,
      version = session.version,
      createdAt = session.createdAt,
      expiresAt = session.expiresAt,
      idleExpiresAt = session.idleExpiresAt,
      userAgent = session.userAgent.map(userAgentResponse),
    )

  /** Every remembered user agent with its live session, or `session: null` for a signed-out one.
    * Separate from [[findSessionsEndpoint]], whose items are always sessions. */
  val findUserAgentsEndpoint =
    Method.GET / "users" / "user-agents" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        sessionService <- ZIO.service[SessionService]
        userId <- request.url.queryZIO[UserId]("id")
        devices <- sessionService.listDevicesByUser(userId)
        responses = devices.map: device =>
          DeviceResponse(
            userAgent = device.userAgent.map(userAgentResponse),
            session = device.session.map(sessionResponse),
            endedSessionIds = device.endedSessionIds,
          )
      yield Response.json(DeviceListResponse(responses).toJson)
    }

  /** Forgets a device: its user agent, session and the refresh tokens bound to that session go.
    * A user agent that is not the user's answers 404. */
  val forgetUserAgentEndpoint =
    Method.DELETE / "users" / "user-agents" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        logoutService <- ZIO.service[LogoutService]
        userId <- request.queryZIO[UserId]("userId")
        userAgentId <- request.queryZIO[UserAgentId]("userAgentId")
        actorId <- request.queryZIO[Option[UserId]]("actorId")
        _ <- actedBy(actorId)
        _ <- Observability.setUserId(userId.toString)
        forgotten <- logoutService.forgetUserAgent(userId, userAgentId)
        response <-
          if forgotten then ZIO.succeed(Response.status(Status.NoContent))
          else Observability.setError("user_agent_not_found").as(Response.status(Status.NotFound))
      yield response
    }

  /** With `sessionId` ends that one session of the user; without it, every session. A
    * session that is not the user's answers 404 rather than being ended: a session id on its
    * own is not proof of anything, since it travels in front-channel logout URLs. */
  val invalidateSessionEndpoint =
    Method.DELETE / "users" / "sessions" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        logoutService <- ZIO.service[LogoutService]
        userId <- request.queryZIO[UserId]("userId")
        sessionId <- request.queryZIO[Option[PublicSessionId]]("sessionId")
        actorId <- request.queryZIO[Option[UserId]]("actorId")
        _ <- actedBy(actorId)
        response <- sessionId match
          case None => logoutService.invalidateAllSessions(userId, RevocationCause.AdminRevoked).as(Response.status(Status.NoContent))
          case Some(publicId) =>
            logoutService.invalidateSession(publicId, userId).flatMap:
              case true => ZIO.succeed(Response.status(Status.NoContent))
              case false => Observability.setError("session_not_found").as(Response.status(Status.NotFound))
      yield response
    }

  /** Revokes the offline access one client holds for the user: its live refresh tokens expire
    * now, and so do the access tokens those grants already issued (`fam:` is recorded with each family, and the client's
    * back channel is told). The user's sessions stay, so this is not a logout. */
  val revokeRefreshTokensEndpoint =
    Method.DELETE / "users" / "refresh-tokens" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        sessionService <- ZIO.service[SessionService]
        userId <- request.queryZIO[UserId]("userId")
        clientId <- request.queryZIO[ClientId]("clientId")
        actorId <- request.queryZIO[Option[UserId]]("actorId")
        _ <- actedBy(actorId)
        _ <- Observability.setUserId(userId.toString)
        _ <- Observability.setClientId(clientId)
        families <- sessionService.revokeRefreshTokensByUserAndClient(userId, clientId)
        revoked = families.size
        client <- ZIO.serviceWithZIO[OAuthConfigurationService](_.find(clientId))
        now <- Clock.instant
        // The record is already committed; the push is for what checks a JWT on its own signature. A client this
        // deployment no longer knows has no back channel to tell.
        _ <- ZIO.foreachDiscard(client): client =>
          ZIO.foreachDiscard(families): family =>
            ZIO.serviceWithZIO[AccessTokenRevocationService](
              _.announceFamily(client, family, subject = userId.toString, expiresAt = now.plus(client.accessTokenTtl)),
            )
        _ <- audited(
          AuthAuditKinds.TokenRevoked,
          userId,
          AuthAuditDetails.TokenRevoked(tokenType = "refresh_token", jti = None, refreshPrefix = None, cause = "admin_revoked", count = Some(revoked)),
        )
      yield Response.status(Status.NoContent)
    }

  /** Independent of findSessionsEndpoint: a refresh token's expiry is fixed at issuance and
    * independent of a session's, so a token routinely outlives the session it was issued
    * under and would be invisible to an admin working from the session list alone. */
  val findRefreshTokensEndpoint =
    Method.GET / "users" / "refresh-tokens" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        sessionService <- ZIO.service[SessionService]
        userId <- request.url.queryZIO[UserId]("id")
        tokens <- sessionService.listRefreshTokensByUser(userId)
        responses = tokens.map { token =>
          RefreshTokenResponse(
            sessionId = token.publicSessionId,
            clientId = token.clientId,
            scope = token.scope.toList,
            issuedAt = token.issuedAt,
            expiresAt = token.expiresAt,
            dpopBound = token.cnf.exists(_.jkt.isDefined),
          )
        }
      yield Response.json(RefreshTokenListResponse(responses).toJson)
    }

  val resetLimitsEndpoint =
    Method.POST / "users" / "limits" / "reset" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        throttleRepo <- ZIO.service[ChallengeThrottleRepository]
        body <- request.body.asJsonFromCodec[ResetUserLimitsPayload]
        _ <- actedBy(body.actorId)
        tenantId = body.tenantId
        subjects = (List(body.userId.toString) ++ body.email.map(_.toString) ++ body.phone.map(_.toString)).distinct
        _ <- ZIO.foreachDiscard(subjects)(throttleRepo.deleteAllForSubject(tenantId, _))
        _ <- audited(AuthAuditKinds.AccountLimitsReset, body.userId, AuthAuditDetails.Account(via = "admin", detail = None))
      yield Response.status(Status.NoContent)
    }

  val listPasskeysEndpoint =
    Method.GET / "users" / "passkeys" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[PasskeyRepository]
        userId <- request.url.queryZIO[UserId]("id")
        records <- repo.listByUser(userId)
        passkeys = records.map: r =>
          PasskeyInfoResponse(r.id, r.name, r.deviceType, r.transports, r.backedUp, r.backupEligible, r.lastUsedAt, r.createdAt)
      yield Response.json(ListPasskeysResponse(passkeys.toList).toJson)
    }

  val renamePasskeyEndpoint =
    Method.PATCH / "users" / "passkeys" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[PasskeyRepository]
        body <- request.body.asJsonFromCodec[RenamePasskeyPayload]
        _ <- repo.rename(body.credentialId, body.userId, body.name)
      yield Response.status(Status.NoContent)
    }

  val deletePasskeyEndpoint =
    Method.DELETE / "users" / "passkeys" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[PasskeyRepository]
        userId <- request.queryZIO[UserId]("id")
        credentialId <- request.queryZIO[CredentialId]("credentialId")
        actorId <- request.queryZIO[Option[UserId]]("actorId")
        _ <- actedBy(actorId)
        _ <- repo.deleteByUser(credentialId, userId)
        _ <- audited(
          AuthAuditKinds.AccountPasskeyRemoved,
          userId,
          AuthAuditDetails.Passkey(credentialPrefix = Some(credentialId.toString.take(9)), deviceType = None),
        )
      yield Response.status(Status.NoContent)
    }

  val resetPasswordEndpoint =
    Method.POST / "users" / "password" / "reset" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.body.asJsonFromCodec[ResetPasswordPayload]
        _ <- actedBy(body.actorId)
        passwordService <- ZIO.service[PasswordService]
        // A reset ends every session of the user with the temporary password (see `PasswordService.resetPassword`);
        // the relying parties of those sessions are told afterwards, best effort, as after any logout.
        reset <- passwordService.resetPassword(body.userId, body.expiresInSeconds, body.channel)
        _ <- ZIO.serviceWithZIO[LogoutService](_.announceEnded(reset.endedSessions, RevocationCause.PasswordChanged))
        _ <- audited(
          AuthAuditKinds.AccountPasswordReset,
          body.userId,
          AuthAuditDetails.Account(via = "admin", detail = body.channel.map(_.toString)),
        )
      yield reset.password match
        case Some(plaintext) => Response.json(ResetPasswordResponse(plaintext).toJson)
        case None => Response.status(Status.NoContent)
    }

  val setPasswordEndpoint =
    Method.POST / "users" / "password" / "set" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.body.asJsonFromCodec[SetPasswordPayload]
        _ <- actedBy(body.actorId)
        passwordService <- ZIO.service[PasswordService]
        // The tokens issued before the change are refused, recorded with the password; sessions and refresh tokens stay.
        terms <- ZIO.serviceWithZIO[OAuthConfigurationService](_.accessTokenTtls).map(RevocationTerms.widest(RevocationCause.PasswordChanged, _))
        _ <- passwordService.setPassword(body.userId, Password(body.password), PasswordChangeEffect.RefuseEarlierTokens(terms))
          .mapError:
            case PasswordReuseError(n) => RuntimeException(s"Password reuse: must differ from last $n passwords")
            case t: Throwable => t
        _ <- audited(AuthAuditKinds.AccountPasswordSet, body.userId, AuthAuditDetails.Account(via = "admin", detail = None))
      yield Response.status(Status.NoContent)
    }
