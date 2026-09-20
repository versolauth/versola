package versola.central.configuration.jwks

import versola.util.{JWT, Secret}
import zio.json.ast.Json

/**
 * A single JSON Web Key stored in the central database.
 *
 * `kid` is the key id (primary key); `jwk` is the raw JWK JSON object as served
 * in the central JWKS.
 *
 * `privateKey` is the PKCS#8 private half -- AES-GCM encrypted with the shared
 * `clientSecretsSecret` at rest, plaintext once [[JwksService]] has read it into its cache --
 * and is `None` for a verify-only key -- one seeded from `bootstrap.jwks`, whose private half
 * central was never given. Only a key that has one can be a tenant's signing key.
 */
case class JwksRecord(
    kid: String,
    jwk: Json.Obj,
    privateKey: Option[Secret],
):
  /** The algorithm this key is published under, read from the JWK itself rather than stored
    * alongside it, so the two cannot disagree. `None` for a JWK with no `alg`, or one naming
    * an algorithm this service cannot sign or verify with.
    */
  def algorithm: Option[JWT.Algorithm] =
    jwk.fields.collectFirst { case ("alg", Json.Str(alg)) => alg }
      .flatMap(JWT.Algorithm.fromName)

  /** A key central can sign with, as opposed to one it can only publish for verification. */
  def canSign: Boolean = privateKey.isDefined && algorithm.isDefined

object JwksRecord:
  /** The order a key is chosen in when nobody has chosen one: `PS256` first.
    *
    * FAPI 2.0 permits `PS256` and `ES256` equally, so the tie is broken on where the cost
    * falls in this system. Verification runs on the edge's proxy path for every request and
    * is not cached (`edge.auth`), while signing runs once per token; `PS256` verifies ~18x
    * faster than `ES256` and signs ~5x slower, so past about 1.4 verifications per issued
    * token it is the cheaper of the two -- and an access token is verified on every API call
    * it is presented for. `RS256` is last: FAPI disallows it, and it is only here for
    * deployments already issuing under it.
    */
  val algorithmPreference: List[JWT.Algorithm] =
    List(JWT.Algorithm.PS256, JWT.Algorithm.ES256, JWT.Algorithm.RS256)

  /** The key a tenant gets when none was selected for it: the most preferred algorithm
    * central can actually sign with, newest kid first. `None` when every stored key is
    * verify-only, which is the state of a deployment seeded from `bootstrap.jwks` -- those
    * fall back to auth's configured private key instead.
    */
  def preferredSigningKey(records: Vector[JwksRecord]): Option[JwksRecord] =
    algorithmPreference.iterator
      .map(algorithm => records.filter(r => r.canSign && r.algorithm.contains(algorithm)))
      .collectFirst { case candidates if candidates.nonEmpty => candidates.maxBy(_.kid) }
