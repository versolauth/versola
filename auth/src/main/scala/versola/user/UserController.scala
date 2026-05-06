package versola.user

import versola.auth.model.TenantId
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.CoreConfig
import versola.util.http.Controller
import versola.util.{Email, Phone}
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.tracing.Tracing

object UserController extends Controller:
  type Env = Tracing & UserRepository & UserRolesRepository & CoreConfig

  def routes: Routes[Env, Throwable] = Routes(
    createUserEndpoint,
    patchUserEndpoint,
    findClaimsEndpoint,
    findRolesEndpoint,
    assignRoleEndpoint,
    removeRoleEndpoint,
  )

  val createUserEndpoint =
    Method.POST / "users" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRepository]
        body <- request.body.asJsonFromCodec[CreateUserPayload]
        _ <- repo.upsert(body.id, body.email, body.phone, body.login, body.claims)
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
        case None         => Response.status(Status.NoContent)
    }

  val findRolesEndpoint =
    Method.GET / "users" / "roles" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRolesRepository]
        id <- request.url.queryZIO[UserId]("id")
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        roles <- repo.findRolesByUserAndTenant(id, tenantId)
      yield Response.json(UserRolesResponse(roles).toJson)
    }

  val patchUserEndpoint =
    Method.PATCH / "users" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRepository]
        body <- request.body.asJsonFromCodec[PatchUserPayload]
        _ <- repo.patch(body.id, body.email, body.phone, body.login, body.claims)
      yield Response.status(Status.NoContent)
    }

  val assignRoleEndpoint =
    Method.POST / "users" / "roles" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRolesRepository]
        body <- request.body.asJson[AssignRolePayload]
        _ <- repo.assignRole(body.userId, body.tenantId, body.roleId)
      yield Response.status(Status.NoContent)
    }

  val removeRoleEndpoint =
    Method.DELETE / "users" / "roles" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[UserRolesRepository]
        body <- request.body.asJson[RemoveRolePayload]
        _ <- repo.removeRole(body.userId, body.tenantId, body.roleId)
      yield Response.status(Status.NoContent)
    }
