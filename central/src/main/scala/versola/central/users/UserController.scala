package versola.central.users

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.util.http.Controller
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
    createUserEndpoint,
    registeredUserEndpoint,
    patchUserEndpoint,
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
          case error: Throwable  => ZIO.fail(error)
    }

  val patchUserEndpoint =
    Method.PATCH / "users" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        body <- request.bodyAs[PatchUserRequest]
        _ <- service.patch(body)
      yield Response.status(Status.Accepted)
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

  val invalidateSessionEndpoint =
    Method.DELETE / "users" / "sessions" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[UserService]
        userId <- request.queryZIO[UserId]("userId")
        _ <- service.invalidateSession(userId)
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
              case None           => Response.status(Status.NoContent)
      yield response
    }

  val setPasswordEndpoint =
    Method.POST / "users" / "password" / "set" -> handler { (request: Request) =>
      ZIO.service[EnvName].flatMap: env =>
        if env.isProd then ZIO.succeed(Response.status(Status.NotFound))
        else for
          _ <- authorizeBasic(request)
          service <- ZIO.service[UserService]
          body <- request.bodyAs[SetPasswordRequest]
          _ <- service.setPassword(body.userId, body.password)
        yield Response.status(Status.NoContent)
    }
