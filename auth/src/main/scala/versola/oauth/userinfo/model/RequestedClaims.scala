package versola.oauth.userinfo.model

import versola.oauth.client.model.Claim
import zio.json.*
import zio.json.ast.Json
import zio.prelude.Equal
import zio.schema.*

/**
 * Represents the claims parameter from the authorization request
 * OpenID Connect Core 1.0 Section 5.5
 *
 * A request may name `userinfo`, `id_token`, or both, so each member defaults to empty.
 */
case class RequestedClaims(
    userinfo: Map[Claim, ClaimRequest] = Map.empty,
    @jsonField("id_token") idToken: Map[Claim, ClaimRequest] = Map.empty,
) derives Schema, Equal

object RequestedClaims:
  given JsonCodec[Claim] = JsonCodec.string.transform(Claim(_), identity[String])
  given JsonFieldEncoder[Claim] = JsonFieldEncoder.string.contramap(identity)
  given JsonFieldDecoder[Claim] = JsonFieldDecoder.string.map(Claim(_))

  private val derivedDecoder = DeriveJsonDecoder.gen[RequestedClaims]

  /** §5.5 makes `userinfo` and `id_token` objects when present; only an individual claim's value may
    * be `null`. A decoder with defaults reads a null member as an absent one, which would let a
    * malformed request through as an empty one, so a null member is refused before decoding.
    */
  given JsonCodec[RequestedClaims] = JsonCodec(
    DeriveJsonEncoder.gen[RequestedClaims],
    JsonDecoder[Json].mapOrFail:
      case Json.Obj(members) if members.exists(_._2 == Json.Null) => Left("a claims member must be an object, not null")
      case json => derivedDecoder.fromJsonAST(json),
  )

  val empty: RequestedClaims = RequestedClaims()

/**
 * Individual claim request with optional constraints
 */
case class ClaimRequest(
    essential: Option[Boolean],
    value: Option[String],
    values: Option[Vector[String]],
) derives Schema, Equal

object ClaimRequest:
  val default: ClaimRequest = ClaimRequest(None, None, None)

  /** §5.5.1: a claim may be requested as `null`, meaning the claim with no constraints. */
  given JsonCodec[ClaimRequest] = JsonCodec(
    DeriveJsonEncoder.gen[ClaimRequest],
    JsonDecoder.option(using DeriveJsonDecoder.gen[ClaimRequest]).map(_.getOrElse(default)),
  )
