package versola.central.users

import versola.central.audit.{Audit, AuditCursor, AuditReportError, AuditReportService, AuditedController, audited}
import versola.central.configuration.clients.ClientId
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.roles.{RoleGrantGuard, RoleService}
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.Observability
import versola.util.{Email, EnvName, PageLimit, Phone, TemporaryPasswordTtl}
import zio.ZIO
import zio.http.{Method, Request, Response, Status, handler}
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.tracing.Tracing

import java.time.Instant

object UserController extends AuditedController:
  type Env = Tracing & CentralConfig & EdgeService & UserService & ResourceService & EnvName & AuditReportService & RoleService

  def endpoints = List(
    findUsersEndpoint,
    getUserAuditEndpoint,
    getUserRolesEndpoint,
    getUserSessionsEndpoint,
    getUserDevicesEndpoint,
    forgetUserAgentEndpoint,
    getUserRefreshTokensEndpoint,
    revokeRefreshTokensEndpoint,
    createUserEndpoint,
    registeredUserEndpoint,
    patchUserEndpoint,
    deleteUserEndpoint,
    patchUserClaimsEndpoint,
    patchRolesEndpoint,
    invalidateSessionEndpoint,
    resetUserLimitsEndpoint,
    listPasskeysEndpoint,
    renamePasskeyEndpoint,
    deletePasskeyEndpoint,
    resetPasswordEndpoint,
    setPasswordEndpoint,
  )

  val findUsersEndpoint =
    (Method.GET / "users").audited(Audit.read(Audit.UserViewed, "user")) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[Option[UserId]]("id")
        _ <- ZIO.foreachDiscard(id)(Audit.target(_))
        email <- request.queryZIO[Option[Email]]("email")
        phone <- request.queryZIO[Option[Phone]]("phone")
        login <- request.queryZIO[Option[Login]]("login")
        result <- (id, email, phone, login) match
          case (Some(id), _, _, _) => service.findById(id).map(_.toVector).asRight
          case (_, Some(email), _, _) => service.findByEmail(email).map(_.toVector).asRight
          case (_, _, Some(phone), _) => service.findByPhone(phone).map(_.toVector).asRight
          case (_, _, _, Some(login)) => service.findByLogin(login).map(_.toVector).asRight
          case _ => ZIO.left(Response.badRequest)
      yield result.fold(
        identity,
        result => Response.json(UserSearchResponse(result).toJson),
      )
    }

  /** The audit report of one user (`audit:read`, not `users:read`: see [[AuditReportService]]): what auth recorded about them and what
    * administrators did to them. The user is named `id`, like on the other routes. Asking is itself an audit event
    * (`admin.audit.queried`), which `AuditHook` records. */
  val getUserAuditEndpoint =
    (Method.GET / "users" / "audit").audited(Audit.read(Audit.AuditQueried, "user")) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        userId <- request.queryZIO[UserId]("id")
        _ <- Audit.target(userId)
        from <- request.queryZIO[Option[Instant]]("from")
        to <- request.queryZIO[Option[Instant]]("to")
        cursor <- request.queryZIO[Option[AuditCursor]]("cursor")
        limit <- request.queryZIO[Option[PageLimit]]("limit")
        service <- ZIO.service[AuditReportService]
        response <- service.userReport(userId, from, to, cursor, limit)
          .map(report => Response.json(report.toJson))
          .catchAll:
            case AuditReportError.Invalid(message) =>
              Observability.setError("invalid_audit_query", Some(message)).as(Response.text(message).status(Status.BadRequest))
            case AuditReportError.Busy =>
              Observability.setError("audit_report_busy").as(
                Response.text("too many audit reports are running, try again").status(Status.ServiceUnavailable),
              )
            case cause: Throwable => ZIO.fail(cause)
      yield response
    }

  val getUserRolesEndpoint =
    (Method.GET / "users" / "roles").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        tenantId <- request.queryZIO[TenantId]("tenantId")
        roles <- service.getRoles(id, tenantId)
      yield Response.json(UserRolesResponse(roles).toJson)
    }

  val getUserSessionsEndpoint =
    (Method.GET / "users" / "sessions").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        sessions <- service.getSessions(id)
      yield Response.json(sessions.toJson)
    }

  /** Every remembered device, signed in (`session`) or signed out (`session: null`). Separate from
    * [[getUserSessionsEndpoint]], whose items are always live sessions. */
  val getUserDevicesEndpoint =
    (Method.GET / "users" / "user-agents").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        devices <- service.getDevices(id)
      yield Response.json(devices.toJson)
    }

  /** Forgets a device: its user agent, its session and the refresh tokens bound to it. 404 when the
    * user agent is not the user's. */
  val forgetUserAgentEndpoint =
    (Method.DELETE / "users" / "user-agents").audited(Audit.user("user-user-agent", Audit.delete)) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          service <- ZIO.service[UserService]
          userId <- request.queryZIO[UserId]("id")
          _ <- Audit.target(userId)
          userAgentId <- request.queryZIO[String]("userAgentId")
          _ <- Observability.setUserId(userId.toString)
          forgotten <- service.forgetUserAgent(userId, userAgentId)
          response <-
            if forgotten then ZIO.succeed(Response.status(Status.NoContent))
            else Observability.setError("user_agent_not_found").as(Response.status(Status.NotFound))
        yield response
    }

  /** Independent of getUserSessionsEndpoint: a refresh token's expiry is fixed at issuance and
    * independent of a session's, so a token routinely outlives the session it was issued
    * under and would be invisible to an admin working from the session list alone. */
  val getUserRefreshTokensEndpoint =
    (Method.GET / "users" / "refresh-tokens").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        tokens <- service.getRefreshTokens(id)
      yield Response.json(tokens.toJson)
    }

  val createUserEndpoint =
    (Method.POST / "users").audited(Audit.user("user", Audit.create)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[CreateUserRequest]
        id <- service.create(body)
        // the user the row is about is known only now
        _ <- Audit.target(id)
      yield Response.json(CreateUserResponse(id).toJson).status(Status.Created))
        .catchAll:
          case UserConflict => ZIO.succeed(Response.status(Status.Conflict))
          case error: Throwable => ZIO.fail(error)
    }

  val registeredUserEndpoint =
    (Method.POST / "users" / "registrations").audited(Audit.Internal) -> handler { (request: Request) =>
      (for
        _ <- authorizeInternal(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[RegisteredUserRequest]
        userId <- service.indexRegistered(body)
      yield Response.json(RegisteredUserResponse(userId).toJson))
        .catchAll:
          case UserIndexConflict => ZIO.succeed(Response.status(Status.Conflict))
          case error: Throwable => ZIO.fail(error)
    }

  val patchUserEndpoint =
    (Method.PATCH / "users").audited(Audit.user("user", Audit.update)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[PatchUserRequest]
        _ <- Audit.target(body.id)
        _ <- Audit.changed(body, "id")
        _ <- service.patch(body)
      yield Response.status(Status.Accepted))
        .catchAll:
          case UserNotFound => ZIO.succeed(Response.status(Status.NotFound))
          case UserConflict => ZIO.succeed(Response.status(Status.Conflict))
          case error: Throwable => ZIO.fail(error)
    }

  /** Synchronous, unlike the other writes: auth is purged before the index row goes, and a
    * failure there leaves the user in place rather than half deleted. The request log line
    * carries the id (and nothing else about the user), which is the audit trail. */
  val deleteUserEndpoint =
    (Method.DELETE / "users").audited(Audit.user("user", Audit.delete)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        _ <- Audit.target(id)
        _ <- Observability.setUserId(id.toString)
        _ <- service.delete(id)
      yield Response.status(Status.NoContent))
        .catchAll:
          case UserNotFound => Observability.setError("user_not_found").as(Response.status(Status.NotFound))
          case error: Throwable => ZIO.fail(error)
    }

  val patchUserClaimsEndpoint =
    (Method.PATCH / "users" / "claims").audited(Audit.user("user-claims", Audit.update)) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[PatchUserClaimsRequest]
        _ <- Audit.target(body.id)
        _ <- Audit.changed(body, "id")
        _ <- service.patchClaims(body.id, body.claims)
      yield Response.status(Status.Accepted)
    }

  val patchRolesEndpoint =
    (Method.PATCH / "users" / "roles").audited(Audit.user("user-roles", Audit.update)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[UpdateUserRolesRequest]
        _ <- Audit.target(body.userId)
        _ <- Audit.tenant(body.tenantId)
        _ <- Audit.changed(body, "userId", "tenantId")
        _ <- RoleGrantGuard.checkAssignment(request, body)
        _ <- service.updateRoles(body)
      yield Response.status(Status.Accepted))
        .catchSome(RoleGrantGuard.refused)
    }

  /** `userId` alone ends every session, as it always did; `id` with `sessionId` ends that
    * one session (a user agent signed out from the console), 404 when it is not the user's. */
  val invalidateSessionEndpoint =
    (Method.DELETE / "users" / "sessions").audited(Audit.user("user-session", Audit.delete)) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          service <- ZIO.service[UserService]
          sessionId <- request.queryZIO[Option[String]]("sessionId")
          response <- sessionId match
            case None =>
              for
                userId <- request.queryZIO[UserId]("userId")
                _ <- Audit.target(userId)
                _ <- service.invalidateSession(userId)
              yield Response.status(Status.NoContent)
            case Some(sessionId) =>
              for
                userId <- request.queryZIO[UserId]("id")
                _ <- Audit.target(userId)
                _ <- Observability.setUserId(userId.toString)
                ended <- service.endSession(userId, sessionId)
                response <-
                  if ended then ZIO.succeed(Response.status(Status.NoContent))
                  else Observability.setError("session_not_found").as(Response.status(Status.NotFound))
              yield response
        yield response
    }

  /** Revokes the offline access one client holds for the user: its refresh tokens stop
    * redeeming, the user's sessions stay. Idempotent, like the all-sessions delete. */
  val revokeRefreshTokensEndpoint =
    (Method.DELETE / "users" / "refresh-tokens").audited(Audit.user("user-refresh-token", Audit.delete)) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          service <- ZIO.service[UserService]
          userId <- request.queryZIO[UserId]("id")
          _ <- Audit.target(userId)
          clientId <- request.queryZIO[ClientId]("clientId")
          _ <- Observability.setUserId(userId.toString)
          _ <- service.revokeRefreshTokens(userId, clientId)
        yield Response.status(Status.NoContent)
    }

  val resetUserLimitsEndpoint =
    (Method.POST / "users" / "limits" / "reset").audited(Audit.user("user-limits", Audit.action("reset"))) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          service <- ZIO.service[UserService]
          body <- request.bodyAs[ResetUserLimitsRequest]
          _ <- Audit.target(body.userId)
          _ <- Audit.tenant(body.tenantId)
          _ <- service.resetLimits(body)
        yield Response.status(Status.Accepted)
    }

  val listPasskeysEndpoint =
    (Method.GET / "users" / "passkeys").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        passkeys <- service.listPasskeys(id)
      yield Response.json(ListPasskeysResponse(passkeys).toJson)
    }

  val renamePasskeyEndpoint =
    (Method.PATCH / "users" / "passkeys").audited(Audit.user("user-passkey", Audit.update)) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[RenamePasskeyRequest]
        _ <- Audit.target(body.userId)
        _ <- Audit.changed(body, "userId")
        _ <- service.renamePasskey(body)
      yield Response.status(Status.Accepted)
    }

  val deletePasskeyEndpoint =
    (Method.DELETE / "users" / "passkeys").audited(Audit.user("user-passkey", Audit.delete)) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        _ <- Audit.target(id)
        credentialId <- request.queryZIO[String]("credentialId")
        _ <- service.deletePasskey(id, credentialId)
      yield Response.status(Status.Accepted)
    }

  val resetPasswordEndpoint =
    (Method.POST / "users" / "password" / "reset").audited(Audit.user(
      "user-password",
      Audit.action("reset"),
      revealsSecret = true,
    )) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        env <- ZIO.service[EnvName]
        service <- ZIO.service[UserService]
        body <- request.bodyAs[ResetPasswordRequest]
        _ <- Audit.target(body.userId)
        // Revealing the plaintext is a non-prod affordance; auth rejects it too,
        // this guard keeps a tampered console from even reaching auth.
        response <-
          if body.channel.contains(DeliveryChannel.show) && env.isProd then
            ZIO.succeed(Response.status(Status.NotFound))
          else if !TemporaryPasswordTtl.isValid(body.expiresInSeconds) then
            Observability.setError("invalid_expires_in").as(Response.text(TemporaryPasswordTtl.violation).status(Status.BadRequest))
          else
            service.resetPassword(body).map:
              case Some(password) => Response.json(ResetPasswordResponse(password).toJson)
              case None => Response.status(Status.NoContent)
      yield response
    }

  val setPasswordEndpoint =
    (Method.POST / "users" / "password" / "set").audited(Audit.user("user-password", Audit.action("set"))) -> handler {
      (request: Request) =>
        ZIO.service[EnvName].flatMap: env =>
          if env.isProd then ZIO.succeed(Response.status(Status.NotFound))
          else
            for
              _ <- authorizeBasic(request)
              service <- ZIO.service[UserService]
              body <- request.bodyAs[SetPasswordRequest]
              _ <- Audit.target(body.userId)
              _ <- service.setPassword(body.userId, body.password)
            yield Response.status(Status.NoContent)
    }
