package versola.central.configuration.jwks

import versola.util.{JWT, SecurityService, Secret}
import zio.json.ast.Json
import zio.{Task, ZIO}

import javax.crypto.SecretKey

/** Generating a signing key is the same operation whether an operator asks for one or
  * bootstrap seeds the first set, and the two must not drift: a seeded key that is encrypted
  * or published differently from a generated one is a key auth can only sign with by accident.
  */
object JwksKeyGeneration:
  /** A generated key, ready to store: the public half as it will be published, the private
    * half already encrypted at rest.
    */
  case class Generated(kid: String, jwk: Json.Obj, privateKey: Secret)

  /** `HS256` is rejected rather than represented: it is a shared secret, so there would be
    * nothing to publish in a JWKS that is not itself the signing key. `RS256` is rejected
    * because nothing may sign a tenant's tokens under it -- see `ChallengeSettingsService`.
    */
  def generate(
      securityService: SecurityService,
      algorithm: JWT.Algorithm,
      encryptionKey: SecretKey,
  ): Task[Generated] =
    for
      (baseKeyId, publicJwk, pkcs8) <- algorithm match
        case JWT.Algorithm.PS256 =>
          securityService.generateRsaKeyPair.map(pair =>
            (pair.keyId, pair.toPublicJwk, pair.privateKey.getEncoded),
          )
        case JWT.Algorithm.ES256 =>
          securityService.generateEcKeyPair.map(pair =>
            (pair.keyId, pair.toPublicJwk, pair.privateKey.getEncoded),
          )
        case JWT.Algorithm.HS256 =>
          ZIO.fail(JwksService.Error("HS256 is not a JWKS signing algorithm"))
        case JWT.Algorithm.RS256 =>
          ZIO.fail(JwksService.Error(
            "RS256 is not generated: FAPI 1.0 Advanced §8.6 and FAPI 2.0 both disallow its " +
              "PKCS#1 v1.5 padding. Generate a PS256 or ES256 key instead.",
          ))
      // Kids are timestamps to the second, so seeding every algorithm in one second would
      // otherwise collide on the primary key.
      kid = s"$baseKeyId-${algorithm.toString.toLowerCase}"
      encrypted <- securityService.encryptAes256(pkcs8, encryptionKey)
    yield Generated(
      kid = kid,
      // RsaKeyPair publishes itself as RS256, being the shape edge assertions needed. The
      // `alg` is what a verifier goes by, so it is set from the algorithm asked for.
      jwk = withField(withField(publicJwk, "kid", kid), "alg", algorithm.toString),
      privateKey = Secret(encrypted),
    )

  private def withField(jwk: Json.Obj, name: String, value: String): Json.Obj =
    Json.Obj(jwk.fields.filterNot(_._1 == name) :+ (name -> Json.Str(value)))
