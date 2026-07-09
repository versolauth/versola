package versola.user

import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.client.model.TenantId
import versola.oauth.conversation.limit.ChallengeThrottleRepository
import versola.oauth.session.{RefreshTokenRepository, SessionRepository}
import versola.oauth.session.model.SessionId
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.CoreConfig
import versola.util.http.Controller
import versola.util.{Base64, Email, Phone}
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps
import zio.json.JsonCodec
import zio.telemetry.opentelemetry.tracing.Tracing

object UserController extends Controller:
  type Env = Tracing & UserRepository & UserRolesRepository & CoreConfig & SessionRepository & RefreshTokenRepository & ChallengeThrottleRepository & PasskeyRepository

  def routes: Routes[Env, Throwable] = Routes(
    upsertUserEndpoint,
    patchClaimsEndpoint,
    patchRolesEndpoint,
    findClaimsEndpoint,
    findRolesEndpoint,
    findSessionsEndpoint,
    invalidateSessionEndpoint,
    resetLimitsEndpoint,
    listPasskeysEndpoint,
    renamePasskeyEndpoint,
    deletePasskeyEndpoint,
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
      clientId: String,
      platform: String,
      os: Option[String],
      browser: Option[String],
      version: Option[String],
      createdAt: String,
  ) derives JsonCodec

  private case class SessionListResponse(sessions: List[SessionResponse]) derives JsonCodec

  val findSessionsEndpoint =
    Method.GET / "users" / "sessions" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        repo <- ZIO.service[SessionRepository]
        userId <- request.url.queryZIO[UserId]("id")
        sessions <- repo.findByUserId(userId)
      yield Response.json(
        SessionListResponse(
          sessions.map { record =>
            SessionResponse(
              clientId = record.clientId,
              platform = record.userAgent.platform,
              os = record.userAgent.os,
              browser = record.userAgent.browser,
              version = record.userAgent.version,
              createdAt = record.createdAt.toString,
            )
          },
        ).toJson,
      )
    }

  val invalidateSessionEndpoint =
      Method.DELETE / "users" / "sessions" -> handler { (request: Request) =>
        for
          _ <- authorizeInternal(request)
          sessionRepo <- ZIO.service[SessionRepository]
          refreshTokenRepo <- ZIO.service[RefreshTokenRepository]
          userId <- request.queryZIO[UserId]("userId")
          _ <- sessionRepo.invalidateByUserId(userId)
          _ <- refreshTokenRepo.deleteByUserId(userId)
        yield Response.status(Status.NoContent)
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
        userId <- request.url.queryZIO[UserId]("id")
        rawCredentialId <- request.url.queryZIO[String]("credentialId")
        credentialId <- ZIO.fromEither(versola.auth.model.CredentialId.fromBase64Url(rawCredentialId))
          .mapError(msg => new RuntimeException(s"Invalid credentialId: $msg"))
        _ <- repo.deleteByUser(credentialId, userId)
      yield Response.status(Status.NoContent)
    }
