package versola.user

import versola.auth.model.TenantId
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.SessionId
import versola.oauth.session.model.{SessionId, SessionRecord}
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.Base64
import versola.util.CoreConfig
import versola.util.MAC
import versola.util.http.Controller
import versola.util.{Base64Url, MAC}
import versola.util.{Email, Phone}
import zio.ZIO
import zio.http.string
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps
import zio.json.JsonCodec
import zio.telemetry.opentelemetry.tracing.Tracing

object UserController extends Controller:
  type Env = Tracing & UserRepository & UserRolesRepository & CoreConfig & SessionRepository

  def routes: Routes[Env, Throwable] = Routes(
    upsertUserEndpoint,
    patchClaimsEndpoint,
    patchRolesEndpoint,
    findClaimsEndpoint,
    findRolesEndpoint,
    findSessionsEndpoint,
    invalidateSessionEndpoint,
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
        repo <- ZIO.service[UserRolesRepository]
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
        repo <- ZIO.service[UserRolesRepository]
        id <- request.url.queryZIO[UserId]("id")
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        roles <- repo.findRolesByUserAndTenant(id, tenantId)
      yield Response.json(UserRolesResponse(roles).toJson)
    }

  private case class SessionResponse(
      id: String,
      clientId: String,
      userAgent: Option[String],
      createdAt: String,
  ) derives JsonCodec

  private case class SessionListResponse(sessions: List[SessionResponse]) derives JsonCodec

  val findSessionsEndpoint =
    Method.GET / "users" / "sessions" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[SessionRepository]
        userId <- request.url.queryZIO[UserId]("id")
        sessions <- repo.findByUser(userId)
      yield Response.json(
        SessionListResponse(
          sessions.map { case (id, record) =>
            SessionResponse(
              id = Base64Url.encode(id),
              clientId = record.clientId,
              userAgent = record.userAgent,
              createdAt = record.createdAt.toString,
            )
          },
        ).toJson,
      )
    }

  val invalidateSessionEndpoint =
    Method.DELETE / "users" / "sessions" / string("sessionId") -> handler { (sessionId: String, request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[SessionRepository]
        userId <- request.url.queryZIO[UserId]("userId")
        id <- ZIO.fromEither(MAC.fromBase64Url(sessionId))
          .mapError(msg => new RuntimeException(s"Invalid session id: $msg"))
        sessionOpt <- repo.find(id)
        _ <- sessionOpt match
          case None =>
            ZIO.fail(new RuntimeException("Session not found"))
          case Some(record) if record.userId != userId =>
            ZIO.fail(new RuntimeException("Session does not belong to user"))
          case Some(_) =>
            repo.invalidate(id)
      yield Response.status(Status.NoContent)
    }
