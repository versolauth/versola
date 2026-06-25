package versola.oauth.client.model

import zio.json.{JsonCodec, JsonFieldDecoder, JsonFieldEncoder}
import zio.prelude.Equal
import zio.schema.*

/** An authentication challenge that the user actually passed during a conversation. Recorded in the
  * session and conversation as the `amr` (Authentication Methods References). Unlike [[AuthFactorType]],
  * this excludes enrollment-only steps (e.g. passkey enrollment) that are not authentication challenges.
  */
enum PassedAuthFactor derives JsonCodec, Schema, Equal:
  case otp, password, passkey

  /** Whether having passed this challenge satisfies a `required` factor, either because it is the
    * same factor or because it is listed as an equivalent in the auth flow configuration.
    * `equivalents` maps a passed-factor name (e.g. `"passkey"`) to the factor names it covers
    * (e.g. `List("otp")`). Evaluated at check time — never persisted.
    */
  def satisfies(required: PassedAuthFactor, equivalents: Map[PassedAuthFactor, Set[PassedAuthFactor]]): Boolean =
    this == required || equivalents.getOrElse(this, Set.empty).contains(required)

object PassedAuthFactor:
  /** Field codecs so `amr` can be stored as a JSON object keyed by the passed factor. */
  given JsonFieldEncoder[PassedAuthFactor] = JsonFieldEncoder.string.contramap(_.toString)
  given JsonFieldDecoder[PassedAuthFactor] =
    JsonFieldDecoder.string.mapOrFail(s => PassedAuthFactor.values.find(_.toString == s).toRight(s"unknown PassedAuthFactor: $s"))

  def fromFactorType(factorType: AuthFactorType): Option[PassedAuthFactor] =
    factorType match
      case AuthFactorType.otp           => Some(PassedAuthFactor.otp)
      case AuthFactorType.password      => Some(PassedAuthFactor.password)
      case AuthFactorType.passkeyEnroll => None
