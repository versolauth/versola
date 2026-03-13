package versola.oauth.userinfo.model

import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.schema.*

/**
 * UserInfo endpoint response
 * OpenID Connect Core 1.0 Section 5.3.2
 *
 * Returns user claims as a JSON object with native types:
 * - Strings as JSON strings
 * - Booleans as JSON booleans
 * - Numbers as JSON numbers
 * - Objects as nested JSON objects (e.g., address)
 * - Arrays as JSON arrays
 */
case class UserInfoResponse(claims: Map[String, Json]) derives Schema:
  def toJsonAST: Json.Obj = Json.Obj(Chunk.fromIterable(claims))

object UserInfoResponse:
  given JsonEncoder[UserInfoResponse] = JsonEncoder[Json].contramap(_.toJsonAST)
  given JsonDecoder[UserInfoResponse] = JsonDecoder[Json].mapOrFail:
    case Json.Obj(fields) => Right(UserInfoResponse(fields.toMap))
    case _ => Left("Expected JSON object for UserInfo response")

