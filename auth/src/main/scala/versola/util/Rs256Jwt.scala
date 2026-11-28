package versola.util

import zio.IO
import zio.json.JsonDecoder

/** Verification of the JWTs cells and central sign to one another (versolauth/versola#1050): RS256 and nothing else, and
  * one that carries an expiry.
  *
  * [[JWT.deserialize]] verifies with whatever the key a `kid` names can verify (an RSA key verifies `PS256` as well as
  * `RS256`) and treats a token with no `exp` as one that does not expire. Both are the right defaults for a token whose
  * issuer is configured by an operator and which is checked further by its caller; neither is acceptable for a token
  * that stands in for a service, so this closes them.
  */
object Rs256Jwt:
  private case class Algorithm(alg: String) derives JsonDecoder

  private case class Expiring(exp: Long) derives JsonDecoder

  /** Verified against `keys` (looked up by the token's `kid`), `typ` `JWT`, `exp` present and not past. The caller still
    * checks what the claims say: `iss`, `aud`. */
  def deserialize[A: JsonDecoder](token: String, keys: JWT.PublicKeys): IO[JWT.Error, A] =
    for
      header <- JWT.parseHeader[Algorithm](token)
      _ <- zio.ZIO.fail(JWT.Error.InvalidSignature).unless(header.alg == "RS256")
      _ <- JWT.parseClaims[Expiring](token)
      claims <- JWT.deserialize[A](token, keys, JWT.Type.JWT)
    yield claims
