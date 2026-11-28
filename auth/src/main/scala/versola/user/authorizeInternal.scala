package versola.user

import versola.audit.{AuditSupport, AuthActors}
import versola.user.model.UserId
import versola.util.audit.AuditContext
import versola.util.http.{IpAddress, Observability, Unauthorized}
import versola.util.{CoreConfig, Rs256Jwt}
import zio.http.{Header, Request}
import zio.json.JsonCodec
import zio.{UIO, ZIO}

/** The administrator behind a call from central, for the audit log: the user id edge verified, which central passes as the optional
  * `actorId` of the call (a query parameter of a `GET` or `DELETE`, a field of the body of a `POST`, `PUT` or `PATCH`). A call without
  * it is the `central` service's own. Call it after [[authorizeInternal]], so that only a verified caller names an actor. */
def actedBy(actorId: Option[UserId]): UIO[Unit] =
  ZIO.foreachDiscard(actorId)(id => AuditContext.setActor(AuthActors.admin, Some(id.toString)))

/** The optional query parameter of a call from central that carries the address the administrator's call to central came from. */
val AdminIpParam = "actorIp"

/** Verifies the service token of a call from central (versolauth/versola#1050): signed RS256 with central's service key,
  * issued by "central", and made for this auth, by its cell id or by "auth" when it has none. A token central made for
  * another cell is refused here, and so is one signed by anybody else, a cell included. Also tells the audit log who
  * acts: the `central` service itself, until the call names an administrator ([[actedBy]]), and from where
  * ([[AdminIpParam]]). */
def authorizeInternal(request: Request): ZIO[CoreConfig, Unauthorized.type, Unit] =
  def refuse(code: String): ZIO[Any, Unauthorized.type, Nothing] = Observability.setError(code) *> ZIO.fail(Unauthorized)

  request.header(Header.Authorization) match
    case Some(Header.Authorization.Bearer(token)) =>
      for
        config <- ZIO.service[CoreConfig]
        claims <- Rs256Jwt.deserialize[InternalAuthClaims](token.stringValue, config.central.publicKeyJwk)
          .tapError(err => ZIO.logError(s"Internal auth failed: $err"))
          .orElse(refuse("internal_token_invalid"))
        // Whose token it is is decided by the key above; what it is for, here. The cell id is this auth's own configuration,
        // never anything the request says.
        audience = config.cellId.getOrElse(InternalAudience)
        _ <- refuse("internal_token_invalid").unless(claims.iss == InternalIssuer && claims.aud.contains(audience))
        _ <- AuditContext.setActor(AuthActors.service, Some("central"))
        // The address of the administrator's call, when central passed one on; a value that is no address is not recorded. Only a
        // caller that proved to be central gets this far, so it is central's word and not the caller's.
        _ <- ZIO.foreachDiscard(request.url.queryParams.map.get(AdminIpParam).flatMap(_.headOption).flatMap(IpAddress.literal))(Observability.setIp)
        _ <- AuditSupport.noteUserAgent(request)
      yield ()

    case _ =>
      ZIO.logWarning("Missing internal auth header") *>
        refuse("internal_token_missing")

/** The `iss` of a token central made for a call to an auth. */
private val InternalIssuer = "central"

/** The `aud` of a token made for an auth that has no cell id. */
private val InternalAudience = "auth"

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
