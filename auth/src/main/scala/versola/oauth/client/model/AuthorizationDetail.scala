package versola.oauth.client.model

import zio.Chunk
import zio.json.ast.Json
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}
import zio.prelude.Equal

/** A single RFC 9396 authorization detail object.
  *
  * Only the members auth itself acts on are parsed out: `type` (required, selects the
  * registered schema) and `locations` (validated against the resource registry, like the
  * RFC 8707 `resource` parameter). Everything else — the common members of §2 and the
  * type-specific ones — is kept verbatim in [[value]], because the AS must echo the granted
  * details back unchanged in the token response and the access token, and must compare them
  * against a later request (§6.1) exactly as they were granted.
  */
case class AuthorizationDetail(
    `type`: AuthorizationDetailType,
    locations: List[ResourceUri],
    value: Json.Obj,
) derives CanEqual:

  /** Key-sorted rendering used to compare a requested detail with a granted one (§6.1),
    * so that member order alone does not make two otherwise identical objects differ. */
  def canonical: Json = AuthorizationDetail.canonicalize(value)

object AuthorizationDetail:
  val Parameter = "authorization_details"

  given Equal[AuthorizationDetail] = Equal.make(_ == _)

  /** Parses one authorization detail object, per RFC 9396 §2: `type` is REQUIRED and must be
    * a string; `locations`, when present, must be an array of resource URIs. */
  def parse(json: Json): Either[String, AuthorizationDetail] =
    for
      obj <- json.asObject.toRight("Authorization detail must be a JSON object")
      typeValue <- obj.get("type").toRight("Authorization detail is missing the required type member")
      typeString <- typeValue.asString.toRight("Authorization detail type must be a string")
      detailType <- AuthorizationDetailType.from(typeString)
      locations <- parseLocations(obj)
    yield AuthorizationDetail(detailType, locations, obj)

  /** Parses the `authorization_details` request parameter: a non-empty JSON array of objects. */
  def parseAll(raw: String): Either[String, List[AuthorizationDetail]] =
    for
      json <- JsonDecoder[Json].decodeJson(raw).left.map(_ => "authorization_details must be valid JSON")
      elements <- json.asArray.toRight("authorization_details must be a JSON array")
      _ <- Either.cond(elements.nonEmpty, (), "authorization_details must not be empty")
      details <- elements.foldLeft[Either[String, List[AuthorizationDetail]]](Right(Nil)):
        case (acc, element) => acc.flatMap(details => parse(element).map(details :+ _))
    yield details

  private def parseLocations(obj: Json.Obj): Either[String, List[ResourceUri]] =
    obj.get("locations") match
      case None => Right(Nil)
      case Some(Json.Arr(elements)) =>
        elements.foldLeft[Either[String, List[ResourceUri]]](Right(Nil)):
          case (acc, element) =>
            for
              locations <- acc
              raw <- element.asString.toRight("Authorization detail locations must be strings")
              location <- ResourceUri.parse(raw)
            yield locations :+ location
      case Some(_) =>
        Left("Authorization detail locations must be an array")

  private def canonicalize(json: Json): Json =
    json match
      case Json.Obj(fields) =>
        Json.Obj(Chunk.fromIterable(fields.sortBy(_._1).map((key, value) => key -> canonicalize(value))))
      case Json.Arr(elements) =>
        Json.Arr(elements.map(canonicalize))
      case other =>
        other

  given JsonEncoder[AuthorizationDetail] = JsonEncoder[Json.Obj].contramap(_.value)
  given JsonDecoder[AuthorizationDetail] = JsonDecoder[Json].mapOrFail(parse)
  given JsonCodec[AuthorizationDetail] = JsonCodec(summon[JsonEncoder[AuthorizationDetail]], summon[JsonDecoder[AuthorizationDetail]])
