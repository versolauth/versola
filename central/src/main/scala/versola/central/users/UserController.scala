package versola.central.users

import versola.central.configuration.tenants.TenantId
import versola.util.http.Controller
import versola.util.{Email, Phone}
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.tracing.Tracing

object UserController extends Controller:
  type Env = Tracing & UserService

  def routes: Routes[Env, Throwable] = Routes(
    findUsersEndpoint,
    getUserRolesEndpoint,
    createUserEndpoint,
    patchUserEndpoint,
    assignRoleEndpoint,
    removeRoleEndpoint,
  )

  val findUsersEndpoint =
    Method.GET / "users" -> handler { (request: Request) =>
      for
        service <- ZIO.service[UserService]
        id <- request.queryZIO[Option[UserId]]("id")
        email <- request.queryZIO[Option[Email]]("email")
        phone <- request.queryZIO[Option[Phone]]("phone")
        login <- request.queryZIO[Option[Login]]("login")
        result <- (id, email, phone, login) match
          case (Some(id), _, _, _)    => service.findById(id).map(_.toVector)
          case (_, Some(email), _, _) => service.findByEmail(email).map(_.toVector)
          case (_, _, Some(phone), _) => service.findByPhone(phone).map(_.toVector)
          case (_, _, _, Some(login)) => service.findByLogin(login).map(_.toVector)
          case _                      => ZIO.fail(new IllegalArgumentException("Missing search parameter"))
      yield Response.json(UserSearchResponse(result).toJson)
    }

  val getUserRolesEndpoint =
    Method.GET / "users" / "roles" -> handler { (request: Request) =>
      for
        service <- ZIO.service[UserService]
        id <- request.queryZIO[UserId]("id")
        tenantId <- request.queryZIO[TenantId]("tenantId")
        roles <- service.getRoles(id, tenantId)
      yield Response.json(UserRolesResponse(roles).toJson)
    }

  val createUserEndpoint =
    Method.POST / "users" -> handler { (request: Request) =>
      (for
        service <- ZIO.service[UserService]
        body <- request.body.asJsonFromCodec[CreateUserRequest]
        id <- service.create(body)
      yield Response.json(CreateUserResponse(id).toJson).status(Status.Created))
        .catchAll:
          case UserConflict     => ZIO.succeed(Response.status(Status.Conflict))
          case error: Throwable => ZIO.fail(error)
    }

  val patchUserEndpoint =
    Method.PATCH / "users" -> handler { (request: Request) =>
      for
        service <- ZIO.service[UserService]
        body <- request.body.asJsonFromCodec[PatchUserRequest]
        _ <- service.patch(body)
      yield Response.status(Status.Accepted)
    }

  val assignRoleEndpoint =
    Method.POST / "users" / "roles" -> handler { (request: Request) =>
      for
        service <- ZIO.service[UserService]
        body <- request.body.asJson[UserRoleRequest]
        _ <- service.assignRole(body)
      yield Response.status(Status.Accepted)
    }

  val removeRoleEndpoint =
    Method.DELETE / "users" / "roles" -> handler { (request: Request) =>
      for
        service <- ZIO.service[UserService]
        body <- request.body.asJson[UserRoleRequest]
        _ <- service.removeRole(body)
      yield Response.status(Status.Accepted)
    }
