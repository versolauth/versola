package versola.e2e.support

import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.{Curve, ECKey}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.time.Instant
import java.util.{Date, UUID}

/** The client half of RFC 7523 `private_key_jwt`: one key pair, the JWK Set a client
  * registers its public half as, and the assertions it signs with the private half.
  *
  * Held for a whole test, like [[DpopProver]], and for the same reason -- the keys a client
  * registered are the only ones its assertions are verified against, so a helper that
  * generated a fresh pair per call would authenticate nothing.
  *
  * `ES256`, which is what auth advertises by default in
  * `token_endpoint_auth_signing_alg_values_supported`.
  */
final class AssertionSigner private (private val signingKey: ECPrivateKey, publicJwk: ECKey):

  /** The `jwks` document to register the client with. */
  val jwks: Json.Obj =
    Json.Obj("keys" -> Json.Arr(publicJwk.toJSONString.fromJson[Json.Obj].toOption.get))

  /** One assertion.
    *
    * @param audience RFC 7523 §3 requires this to name the server the assertion is sent to;
    *   auth accepts its issuer identifier or the endpoint's own URL.
    * @param jti reused deliberately by the replay test -- every other caller wants a fresh
    *   one, which is the default.
    * @param lifetime how far ahead `exp` sits, which the tenant's configured ceiling bounds.
    */
  def assertion(
      clientId: String,
      audience: String,
      jti: Option[String] = None,
      lifetime: Duration = 60.seconds,
      signWith: Option[ECPrivateKey] = None,

  ): Task[String] =
    ZIO.attempt:
      val claims = JWTClaimsSet.Builder()
        .issuer(clientId)
        .subject(clientId)
        .audience(audience)
        .jwtID(jti.getOrElse(UUID.randomUUID().toString))
        .expirationTime(Date.from(Instant.now().plusSeconds(lifetime.toSeconds)))
        .build()
      val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(publicJwk.getKeyID).build(), claims)
      jwt.sign(ECDSASigner(signWith.getOrElse(signingKey)))
      jwt.serialize()

  /** The private half of a pair this client never registered, for the assertion that has to
    * be refused however well formed it is. */
  def foreignKey: Task[ECPrivateKey] = AssertionSigner.make.map(_.signingKey)

  /** RFC 9101 §4: a signed request object, carrying whatever claims the caller supplies
    * (typically the same parameters a plain authorization request would have sent, plus
    * `iss`/`aud`/`exp`/`client_id`) rather than the fixed assertion shape [[assertion]] signs.
    */
  def requestObject(claims: (String, Json)*)(signWith: Option[ECPrivateKey] = None): Task[String] =
    ZIO.attempt:
      val payload = Json.Obj(Chunk.fromIterable(claims)*)
      val jwt = SignedJWT(
        JWSHeader.Builder(JWSAlgorithm.ES256).keyID(publicJwk.getKeyID).build(),
        JWTClaimsSet.parse(payload.toString),
      )
      jwt.sign(ECDSASigner(signWith.getOrElse(signingKey)))
      jwt.serialize()

object AssertionSigner:

  /** RFC 7523 §2.2: the fixed `client_assertion_type` a token request carries. */
  val Type = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"

  def make: Task[AssertionSigner] =
    ZIO.attempt:
      val generator = java.security.KeyPairGenerator.getInstance("EC").nn
      generator.initialize(Curve.P_256.toECParameterSpec)
      val pair = generator.generateKeyPair().nn
      AssertionSigner(
        pair.getPrivate.asInstanceOf[ECPrivateKey],
        ECKey.Builder(Curve.P_256, pair.getPublic.asInstanceOf[ECPublicKey])
          .keyID(UUID.randomUUID().toString)
          .build(),
      )
