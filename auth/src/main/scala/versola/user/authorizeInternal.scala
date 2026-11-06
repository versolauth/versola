package versola.user

import versola.audit.{AuditSupport, AuthActors}
import versola.user.model.UserId
import versola.util.audit.AuditContext
import versola.util.http.Unauthorized
import versola.util.{CoreConfig, JWT}
import zio.http.{Header, Request}
import zio.json.JsonCodec
import zio.{UIO, ZIO}

/** The administrator behind a call from central, for the audit log: the user id edge verified, which central passes as the optional
  * `actorId` of the call (a query parameter of a `GET` or `DELETE`, a field of the body of a `POST`, `PUT` or `PATCH`). A call without
  * it is the `central` service's own. Call it after [[authorizeInternal]], so that only a verified caller names an actor. */
def actedBy(actorId: Option[UserId]): UIO[Unit] =
  ZIO.foreachDiscard(actorId)(id => AuditContext.setActor(AuthActors.admin, Some(id.toString)))

/** Verifies the service token of a call from central. Also tells the audit log who acts: the `central` service itself, until the
  * call names an administrator ([[actedBy]]). */
def authorizeInternal(request: Request): ZIO[CoreConfig, Unauthorized.type, Unit] =
  request.header(Header.Authorization) match
    case Some(Header.Authorization.Bearer(token)) =>
      for
        config <- ZIO.service[CoreConfig]
        _ <- JWT.deserialize[InternalAuthClaims](token.stringValue, config.central.secretKey, JWT.Type.JWT)
          .tapError(err => ZIO.logError(s"Internal auth failed: $err"))
          .orElseFail(Unauthorized)
        _ <- AuditContext.setActor(AuthActors.service, Some("central"))
        _ <- AuditSupport.noteUserAgent(request)
      yield ()

    case _ =>
      ZIO.logWarning("Missing internal auth header") *>
        ZIO.fail(Unauthorized)

case class InternalAuthClaims(
    iss: String,
    sub: String,
    aud: List[String],
)

object InternalAuthClaims:
  import zio.json.ast.Json
  given JsonCodec[InternalAuthClaims] = JsonCodec[Json].transformOrFail(
    json =>
      for
        obj <- json.asObject.toRight("InternalAuthClaims must be an object")
        iss <- obj.get("iss").flatMap(_.asString).toRight("Missing or invalid iss")
        sub <- obj.get("sub").flatMap(_.asString).toRight("Missing or invalid sub")
        audJson <- obj.get("aud").toRight("Missing aud")
        aud <- audJson match
          case Json.Str(s) => Right(List(s))
          case Json.Arr(arr) =>
            val strings = arr.collect { case Json.Str(s) => s }
            if strings.length == arr.length then Right(strings.toList)
            else Left("All aud elements must be strings")
          case _ => Left("aud must be string or array")
      yield InternalAuthClaims(iss, sub, aud),
    claims =>
      Json.Obj(
        zio.Chunk(
          "iss" -> Json.Str(claims.iss),
          "sub" -> Json.Str(claims.sub),
          "aud" -> (if claims.aud.length == 1 then Json.Str(claims.aud.head)
                    else Json.Arr(zio.Chunk.fromIterable(claims.aud.map(Json.Str(_))))),
        ),
      ),
  )
