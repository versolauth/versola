package versola.oauth.userinfo.model

import versola.oauth.client.model.Claim
import zio.json.*
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
  given JsonCodec[RequestedClaims] = DeriveJsonCodec.gen[RequestedClaims]

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
