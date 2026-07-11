package versola.user

import versola.util.{CoreConfig, JWT}
import versola.util.http.Unauthorized
import zio.ZIO
import zio.http.{Header, Request}
import zio.json.JsonCodec

def authorizeInternal(request: Request): ZIO[CoreConfig, Unauthorized.type, Unit] =
  request.header(Header.Authorization) match
    case Some(Header.Authorization.Bearer(token)) =>
      for
        config <- ZIO.service[CoreConfig]
        _ <- JWT.deserialize[InternalAuthClaims](token.stringValue, config.central.secretKey)
          .tapError(err => ZIO.logError(s"Internal auth failed: $err"))
          .orElseFail(Unauthorized)
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
        )
      )
  )
