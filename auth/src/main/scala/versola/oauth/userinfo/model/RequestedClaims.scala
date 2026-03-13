package versola.oauth.userinfo.model

import versola.oauth.client.model.Claim
import zio.json.*
import zio.schema.*

/**
 * Represents the claims parameter from the authorization request
 * OpenID Connect Core 1.0 Section 5.5
 */
case class RequestedClaims(
    userinfo: Map[Claim, ClaimRequest],
    @jsonField("id_token") idToken: Map[String, ClaimRequest],
) derives Schema

object RequestedClaims:
  given JsonCodec[Claim] = JsonCodec.string.transform(Claim(_), identity[String])
  given JsonCodec[RequestedClaims] = DeriveJsonCodec.gen[RequestedClaims]

  val empty: RequestedClaims = RequestedClaims(Map.empty, Map.empty)
  given JsonFieldEncoder[Claim] = JsonFieldEncoder.string.contramap(identity)
  given JsonFieldDecoder[Claim] = JsonFieldDecoder.string.map(Claim(_))

/**
 * Individual claim request with optional constraints
 */
case class ClaimRequest(
    essential: Option[Boolean],
    value: Option[String],
    values: Option[Vector[String]],
) derives Schema, JsonCodec

object ClaimRequest:
  val default: ClaimRequest = ClaimRequest(None, None, None)

