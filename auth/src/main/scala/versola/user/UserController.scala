package versola.user

import versola.audit.AuditSupport.*
import versola.audit.AuthActors
import versola.audit.AuthAuditDetails
import versola.audit.AuthAuditKinds
import versola.auth.model.CredentialId
import versola.auth.model.Password
import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.PasswordReuseError
import versola.oauth.client.model.{ClientId, TenantId}
import versola.oauth.conversation.limit.ChallengeThrottleRepository
import versola.oauth.logout.LogoutService
import versola.oauth.session.SessionService
import versola.oauth.session.model.{PublicSessionId, SessionId, SessionUnderUserAgent, UserAgentId, UserAgentRecord}
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.CoreConfig
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.http.{Controller, Observability}
import versola.util.{Email, Phone}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps
import zio.json.JsonCodec
import zio.telemetry.opentelemetry.tracing.Tracing

object UserController extends Controller:
  type Env =
    Tracing & UserRepository & CoreConfig & LogoutService & SessionService & ChallengeThrottleRepository & PasskeyRepository & PasswordService &
      AuditEvents

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
        _ <- logoutService.invalidateAllSessions(id, "user_deleted")
        _ <- repo.delete(id)
        _ <- audited(AuthAuditKinds.AccountDeleted, id, AuthAuditDetails.Account(via = "admin"), asService)
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
        _ <- repo.updateRoles(body.userId, body.tenantId, body.add, body.remove)
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
          case None => logoutService.invalidateAllSessions(userId, "admin_revoked").as(Response.status(Status.NoContent))
          case Some(publicId) =>
            logoutService.invalidateSession(publicId, userId).flatMap:
              case true => ZIO.succeed(Response.status(Status.NoContent))
              case false => Observability.setError("session_not_found").as(Response.status(Status.NotFound))
      yield response
    }

  /** Revokes the offline access one client holds for the user: its live refresh tokens expire
    * now, the user's sessions stay, and access tokens already issued run out on their own. */
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
        revoked <- sessionService.revokeRefreshTokensByUserAndClient(userId, clientId)
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
        _ <- audited(AuthAuditKinds.AccountLimitsReset, body.userId, AuthAuditDetails.Account(via = "admin"))
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
        _ <- audited(AuthAuditKinds.AccountPasskeyRemoved, userId, AuthAuditDetails.Passkey(credentialPrefix = Some(credentialId.toString.take(9))))
      yield Response.status(Status.NoContent)
    }

  val resetPasswordEndpoint =
    Method.POST / "users" / "password" / "reset" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.body.asJsonFromCodec[ResetPasswordPayload]
        _ <- actedBy(body.actorId)
        passwordService <- ZIO.service[PasswordService]
        password <- passwordService.resetPassword(body.userId, body.expiresInSeconds, body.channel)
        _ <- audited(
          AuthAuditKinds.AccountPasswordReset,
          body.userId,
          AuthAuditDetails.Account(via = "admin", detail = body.channel.map(_.toString)),
        )
      yield password match
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
        _ <- passwordService.setPassword(body.userId, Password(body.password))
          .mapError:
            case PasswordReuseError(n) => RuntimeException(s"Password reuse: must differ from last $n passwords")
            case t: Throwable => t
        _ <- audited(AuthAuditKinds.AccountPasswordSet, body.userId, AuthAuditDetails.Account(via = "admin"))
      yield Response.status(Status.NoContent)
    }
