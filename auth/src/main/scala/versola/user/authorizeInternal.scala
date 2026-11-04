package versola.user

import versola.util.audit.{ActorType, AuditContext}
import versola.util.http.Unauthorized
import versola.util.{CoreConfig, JWT}
import zio.ZIO
import zio.http.{Header, Request}
import zio.json.JsonCodec

import java.util.UUID
import scala.util.Try

/** Who the administrator behind a call from central is, for the audit log: the user id edge verified, which central forwards as
  * this header on the calls it makes for one. */
val ActorHeader = "X-Versola-Actor"

/** Verifies the service token of a call from central. Also tells the audit log who acts: the administrator named by
  * [[ActorHeader]] when it carries a user id, else the `central` service itself (a call that no administrator made, or one from
  * a central that does not send the header yet). The header is read only once the token has verified, so no one else can name an actor. */
def authorizeInternal(request: Request): ZIO[CoreConfig, Unauthorized.type, Unit] =
  request.header(Header.Authorization) match
    case Some(Header.Authorization.Bearer(token)) =>
      for
        config <- ZIO.service[CoreConfig]
        _ <- JWT.deserialize[InternalAuthClaims](token.stringValue, config.central.secretKey, JWT.Type.JWT)
          .tapError(err => ZIO.logError(s"Internal auth failed: $err"))
          .orElseFail(Unauthorized)
        // Only a user id is taken as an administrator: anything else is no one's, and the call is the service's.
        admin = request.headers.get(ActorHeader).map(_.trim).filter(value => Try(UUID.fromString(value)).isSuccess)
        _ <- admin match
          case Some(id) => AuditContext.setActor(ActorType.admin, Some(id))
          case None => AuditContext.setActor(ActorType.service, Some("central"))
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
