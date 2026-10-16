package versola.user

import versola.auth.model.CredentialId
import versola.auth.model.Password
import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.PasswordReuseError
import versola.oauth.client.model.{ClientId, TenantId}
import versola.oauth.conversation.limit.ChallengeThrottleRepository
import versola.oauth.logout.LogoutService
import versola.oauth.session.SessionService
import versola.oauth.session.model.{PublicSessionId, SessionId}
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.CoreConfig
import versola.util.http.{Controller, Observability}
import versola.util.{Email, Phone}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps
import zio.json.JsonCodec
import zio.telemetry.opentelemetry.tracing.Tracing

object UserController extends Controller:
  type Env =
    Tracing & UserRepository & CoreConfig & LogoutService & SessionService & ChallengeThrottleRepository & PasskeyRepository & PasswordService

  def routes: Routes[Env, Throwable] = Routes(
    upsertUserEndpoint,
    deleteUserEndpoint,
    patchClaimsEndpoint,
    patchRolesEndpoint,
    findClaimsEndpoint,
    findRolesEndpoint,
    findSessionsEndpoint,
    invalidateSessionEndpoint,
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
        _ <- repo.upsert(body.id, body.version, body.email, body.phone, body.login)
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
        _ <- logoutService.invalidateAllSessions(id)
        _ <- repo.delete(id)
      yield Response.status(Status.NoContent)
    }

  val patchClaimsEndpoint =
    Method.PATCH / "users" / "claims" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRepository]
        body <- request.body.asJsonFromCodec[PatchUserClaimsPayload]
        _ <- repo.patchClaims(body.id, body.claims)
      yield Response.status(Status.NoContent)
    }

  val patchRolesEndpoint =
    Method.PATCH / "users" / "roles" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRepository]
        body <- request.body.asJsonFromCodec[UpdateUserRolesPayload]
        _ <- repo.updateRoles(body.userId, body.tenantId, body.add, body.remove)
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
        responses = sessions.map { session =>
          SessionResponse(
            publicId = session.publicId,
            clients = session.clients.map(c => ClientEntryResponse(c.clientId, c.enteredAt)),
            platform = session.platform,
            os = session.os,
            browser = session.browser,
            version = session.version,
            createdAt = session.createdAt,
          )
        }
      yield Response.json(SessionListResponse(responses).toJson)
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
        response <- sessionId match
          case None => logoutService.invalidateAllSessions(userId).as(Response.status(Status.NoContent))
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
        _ <- Observability.setUserId(userId.toString)
        _ <- sessionService.revokeRefreshTokensByUserAndClient(userId, clientId)
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
        tenantId = body.tenantId
        subjects = (List(body.userId.toString) ++ body.email.map(_.toString) ++ body.phone.map(_.toString)).distinct
        _ <- ZIO.foreachDiscard(subjects)(throttleRepo.deleteAllForSubject(tenantId, _))
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
        _ <- repo.deleteByUser(credentialId, userId)
      yield Response.status(Status.NoContent)
    }

  val resetPasswordEndpoint =
    Method.POST / "users" / "password" / "reset" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.body.asJsonFromCodec[ResetPasswordPayload]
        passwordService <- ZIO.service[PasswordService]
        password <- passwordService.resetPassword(body.userId, body.expiresInSeconds, body.channel)
      yield password match
        case Some(plaintext) => Response.json(ResetPasswordResponse(plaintext).toJson)
        case None => Response.status(Status.NoContent)
    }

  val setPasswordEndpoint =
    Method.POST / "users" / "password" / "set" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.body.asJsonFromCodec[SetPasswordPayload]
        passwordService <- ZIO.service[PasswordService]
        _ <- passwordService.setPassword(body.userId, Password(body.password))
          .mapError:
            case PasswordReuseError(n) => RuntimeException(s"Password reuse: must differ from last $n passwords")
            case t: Throwable => t
      yield Response.status(Status.NoContent)
    }
