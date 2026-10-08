package versola.oauth.userinfo.model

import versola.oauth.client.model.Claim
import zio.json.*
import zio.prelude.Equal
import zio.schema.*

/**
 * Represents the claims parameter from the authorization request
 * OpenID Connect Core 1.0 Section 5.5
 */
case class RequestedClaims(
    userinfo: Map[Claim, ClaimRequest],
    @jsonField("id_token") idToken: Map[Claim, ClaimRequest],
) derives Schema, Equal

object RequestedClaims:
  given JsonCodec[Claim] = JsonCodec.string.transform(Claim(_), identity[String])
  given JsonFieldEncoder[Claim] = JsonFieldEncoder.string.contramap(identity)
  given JsonFieldDecoder[Claim] = JsonFieldDecoder.string.map(Claim(_))

  /** OpenID Connect Core §5.5 as it is written, not as it is stored: a request names `userinfo`,
    * `id_token`, or both, and a claim's value may be `null` -- "this claim, with no constraints"
    * (`"nickname": null` in the spec's own example). Decoding straight into [[RequestedClaims]]
    * made both members required and refused the null, so `{"userinfo":{"name":{"essential":true}}}`
    * was answered "must be valid JSON". Encoding is unchanged, so what is already stored
    * (both members always written) decodes as before.
    */
  private case class Wire(
      userinfo: Option[Map[Claim, Option[ClaimRequest]]],
      @jsonField("id_token") idToken: Option[Map[Claim, Option[ClaimRequest]]],
  ) derives JsonDecoder

  private def resolved(members: Option[Map[Claim, Option[ClaimRequest]]]): Map[Claim, ClaimRequest] =
    members.getOrElse(Map.empty).map((claim, request) => claim -> request.getOrElse(ClaimRequest.default))

  given JsonCodec[RequestedClaims] = JsonCodec(
    DeriveJsonEncoder.gen[RequestedClaims],
    JsonDecoder[Wire].map(wire => RequestedClaims(resolved(wire.userinfo), resolved(wire.idToken))),
  )

  val empty: RequestedClaims = RequestedClaims(Map.empty, Map.empty)

/**
 * Individual claim request with optional constraints
 */
case class ClaimRequest(
    essential: Option[Boolean],
    value: Option[String],
    values: Option[Vector[String]],
) derives Schema, JsonCodec, Equal

object ClaimRequest:
  val default: ClaimRequest = ClaimRequest(None, None, None)
