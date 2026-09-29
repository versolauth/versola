package versola.central.configuration.clients

import com.nimbusds.jose.jwk.{Curve, ECKey, JWK, KeyUse, RSAKey}
import versola.util.{ClientAssertion, JsonWebKeySet, PrivateJsonWebKey, SecurityService}
import zio.json.DecoderOps
import zio.json.ast.Json
import zio.{Task, ZIO}

/** The key pair a `private_key_jwt` client signs its assertions with, generated at
  * registration rather than registered.
  *
  * Registration otherwise only accepts a key set the caller already holds, which leaves
  * obtaining one the caller's problem. In a FAPI 2.0 tenant that is the whole of what stands
  * between an operator and a working service client: the profile does not admit
  * `client_secret`, so there is no method left whose credential this server issues.
  *
  * The private half is returned and never stored. `jwks` keeps the public half, as a
  * registered key set always has, so there is no column for the private one to leak through,
  * nothing for the edge sync to carry, and no second copy a rotation would have to reach. A
  * caller that loses it registers another key rather than asking for this one again.
  */
object ClientKeyGeneration:

  /** @param publicKeys the half stored on the record, as [[OAuthClientRecord.jwks]] holds it
    * @param privateKey the half handed back to the caller, exactly once
    */
  case class Generated(publicKeys: JsonWebKeySet, privateKey: PrivateJsonWebKey)

  /** @param algorithm what the client will sign assertions with. Decides the key type, which
    *                  is why it is asked for rather than defaulted: an `ES256` client cannot
    *                  use an RSA key, and learning that at the token endpoint costs an
    *                  `invalid_client` with nothing naming the mismatch.
    */
  def generate(
      securityService: SecurityService,
      algorithm: ClientAssertion.Algorithm,
  ): Task[Generated] =
    for
      jwk <- algorithm match
        case ClientAssertion.Algorithm.ES256 =>
          securityService.generateEcKeyPair.map: pair =>
            ECKey.Builder(Curve.P_256, pair.publicKey)
              .privateKey(pair.privateKey)
              .keyID(pair.keyId)
              .algorithm(algorithm.jwsAlgorithm)
              .keyUse(KeyUse.SIGNATURE)
              .build(): JWK
        case ClientAssertion.Algorithm.PS256 | ClientAssertion.Algorithm.RS256 =>
          securityService.generateRsaKeyPair.map: pair =>
            RSAKey.Builder(pair.publicKey)
              .privateKey(pair.privateKey)
              .keyID(pair.keyId)
              .algorithm(algorithm.jwsAlgorithm)
              .keyUse(KeyUse.SIGNATURE)
              .build(): JWK
      // Derived from the private key rather than built beside it: the two halves agreeing is
      // what makes the returned key usable against the stored set, and deriving is the only
      // way that cannot come apart under a later edit to either builder.
      privateDocument <- document(jwk.toJSONString)
      publicDocument <- document(jwk.toPublicJWK.toJSONString)
    yield Generated(
      publicKeys = JsonWebKeySet(Json.Obj("keys" -> Json.Arr(publicDocument))),
      privateKey = PrivateJsonWebKey(privateDocument),
    )

  private def document(json: String): Task[Json.Obj] =
    ZIO.fromEither(json.fromJson[Json.Obj])
      .mapError(reason => IllegalStateException(s"generated key is not a JWK document: $reason"))
