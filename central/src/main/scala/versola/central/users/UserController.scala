package versola.central.users

import versola.central.configuration.clients.ClientId
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.{Controller, Observability}
import versola.util.{Email, EnvName, Phone}
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.tracing.Tracing

object UserController extends Controller:
  type Env = Tracing & CentralConfig & EdgeService & UserService & ResourceService & EnvName

  def routes: Routes[Env, Throwable] = Routes(
    findUsersEndpoint,
    getUserRolesEndpoint,
    getUserSessionsEndpoint,
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
    Method.GET / "users" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[Option[UserId]]("id")
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

  val getUserRolesEndpoint =
    Method.GET / "users" / "roles" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        tenantId <- request.queryZIO[TenantId]("tenantId")
        roles <- service.getRoles(id, tenantId)
      yield Response.json(UserRolesResponse(roles).toJson)
    }

  val getUserSessionsEndpoint =
    Method.GET / "users" / "sessions" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        sessions <- service.getSessions(id)
      yield Response.json(sessions.toJson)
    }

  /** Independent of getUserSessionsEndpoint: a refresh token's expiry is fixed at issuance and
    * independent of a session's, so a token routinely outlives the session it was issued
    * under and would be invisible to an admin working from the session list alone. */
  val getUserRefreshTokensEndpoint =
    Method.GET / "users" / "refresh-tokens" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        tokens <- service.getRefreshTokens(id)
      yield Response.json(tokens.toJson)
    }

  val createUserEndpoint =
    Method.POST / "users" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[CreateUserRequest]
        id <- service.create(body)
      yield Response.json(CreateUserResponse(id).toJson).status(Status.Created))
        .catchAll:
          case UserConflict => ZIO.succeed(Response.status(Status.Conflict))
          case error: Throwable => ZIO.fail(error)
    }

  val registeredUserEndpoint =
    Method.POST / "users" / "registrations" -> handler { (request: Request) =>
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
    Method.PATCH / "users" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[PatchUserRequest]
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
    Method.DELETE / "users" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        _ <- Observability.setUserId(id.toString)
        _ <- service.delete(id)
      yield Response.status(Status.NoContent))
        .catchAll:
          case UserNotFound => Observability.setError("user_not_found").as(Response.status(Status.NotFound))
          case error: Throwable => ZIO.fail(error)
    }

  val patchUserClaimsEndpoint =
    Method.PATCH / "users" / "claims" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[PatchUserClaimsRequest]
        _ <- service.patchClaims(body.id, body.claims)
      yield Response.status(Status.Accepted)
    }

  val patchRolesEndpoint =
    Method.PATCH / "users" / "roles" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[UpdateUserRolesRequest]
        _ <- service.updateRoles(body)
      yield Response.status(Status.Accepted)
    }

  /** `userId` alone ends every session, as it always did; `id` with `sessionId` ends that
    * one session (a user agent signed out from the console), 404 when it is not the user's. */
  val invalidateSessionEndpoint =
    Method.DELETE / "users" / "sessions" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        sessionId <- request.queryZIO[Option[String]]("sessionId")
        response <- sessionId match
          case None =>
            for
              userId <- request.queryZIO[UserId]("userId")
              _ <- service.invalidateSession(userId)
            yield Response.status(Status.NoContent)
          case Some(sessionId) =>
            for
              userId <- request.queryZIO[UserId]("id")
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
    Method.DELETE / "users" / "refresh-tokens" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        userId <- request.queryZIO[UserId]("id")
        clientId <- request.queryZIO[ClientId]("clientId")
        _ <- Observability.setUserId(userId.toString)
        _ <- service.revokeRefreshTokens(userId, clientId)
      yield Response.status(Status.NoContent)
    }

  val resetUserLimitsEndpoint =
    Method.POST / "users" / "limits" / "reset" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[ResetUserLimitsRequest]
        _ <- service.resetLimits(body)
      yield Response.status(Status.Accepted)
    }

  val listPasskeysEndpoint =
    Method.GET / "users" / "passkeys" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        passkeys <- service.listPasskeys(id)
      yield Response.json(ListPasskeysResponse(passkeys).toJson)
    }

  val renamePasskeyEndpoint =
    Method.PATCH / "users" / "passkeys" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[RenamePasskeyRequest]
        _ <- service.renamePasskey(body)
      yield Response.status(Status.Accepted)
    }

  val deletePasskeyEndpoint =
    Method.DELETE / "users" / "passkeys" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        credentialId <- request.queryZIO[String]("credentialId")
        _ <- service.deletePasskey(id, credentialId)
      yield Response.status(Status.Accepted)
    }

  val resetPasswordEndpoint =
    Method.POST / "users" / "password" / "reset" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        env <- ZIO.service[EnvName]
        service <- ZIO.service[UserService]
        body <- request.bodyAs[ResetPasswordRequest]
        // Revealing the plaintext is a non-prod affordance; auth rejects it too,
        // this guard keeps a tampered console from even reaching auth.
        response <-
          if body.channel.contains(DeliveryChannel.show) && env.isProd then
            ZIO.succeed(Response.status(Status.NotFound))
          else
            service.resetPassword(body).map:
              case Some(password) => Response.json(ResetPasswordResponse(password).toJson)
              case None => Response.status(Status.NoContent)
      yield response
    }

  val setPasswordEndpoint =
    Method.POST / "users" / "password" / "set" -> handler { (request: Request) =>
      ZIO.service[EnvName].flatMap: env =>
        if env.isProd then ZIO.succeed(Response.status(Status.NotFound))
        else
          for
            _ <- authorizeBasic(request)
            service <- ZIO.service[UserService]
            body <- request.bodyAs[SetPasswordRequest]
            _ <- service.setPassword(body.userId, body.password)
          yield Response.status(Status.NoContent)
    }
