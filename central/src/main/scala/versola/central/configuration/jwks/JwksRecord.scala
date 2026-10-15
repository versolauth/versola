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
  /** The order a key is chosen in when nobody has chosen one: `ES256` first, `PS256` as the fallback.
    *
    * FAPI 2.0 permits both equally, so the tie is broken on where the cost falls. Without a native
    * provider it falls on the edge: verification runs on its proxy path for every request and is not
    * cached (`edge.auth`), and `ES256` verifies ~8x slower than `PS256` on the JDK, so choosing it only
    * moves the cost from auth (signing, 8x cheaper) to edge. With the native crypto provider (ACCP,
    * which the auth and edge images require at start, see `CryptoProvider`) that disappears: measured
    * 1 auth + 1 edge, `ES256` takes ~14% fewer cores per request than `PS256` (auth 0.8 against 1.1 per
    * 1k steps/s, edge unchanged) and the ceiling is the same. `PS256` stays the fallback for a key set
    * that has no usable `ES256` key. `RS256` is absent: FAPI disallows its PKCS#1 v1.5 padding, so it
    * is never a default -- a `standard` tenant that needs it selects an RS256 key explicitly.
    *
    * Only tenants created, and deployments seeded, from now on are affected: a tenant that already has a
    * `signingKeyId` keeps it, and one without keeps signing with auth's configured key.
    */
  val algorithmPreference: List[JWT.Algorithm] =
    List(JWT.Algorithm.ES256, JWT.Algorithm.PS256)

  /** The key a tenant gets when none was selected for it: the most preferred algorithm
    * central can actually sign with, newest kid first. `None` when every stored key is
    * verify-only, which is the state of a deployment seeded from `bootstrap.jwks` -- those
    * fall back to auth's configured private key instead.
    */
  def preferredSigningKey(records: Vector[JwksRecord]): Option[JwksRecord] =
    algorithmPreference.iterator
      .map(algorithm => records.filter(r => r.canSign && r.algorithm.contains(algorithm)))
      .collectFirst { case candidates if candidates.nonEmpty => candidates.maxBy(_.kid) }
