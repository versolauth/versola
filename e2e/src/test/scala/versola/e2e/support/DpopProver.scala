package versola.e2e.support

import com.nimbusds.jose.crypto.{ECDSASigner, RSASSASigner}
import com.nimbusds.jose.jwk.{Curve, ECKey, JWK, RSAKey}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader, JWSSigner}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import zio.*
import zio.http.{Method, Response}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.{Base64, Date, UUID}

/** The client half of RFC 9449: one key pair, and the proofs it signs.
  *
  * Held for a whole test rather than built per request, because that is the point of the
  * mechanism — the key a token gets bound to at `/token` is the same key that has to prove
  * possession at every resource server afterwards, and a helper that generated a fresh one
  * per call would pass while proving nothing.
  *
  * [[DpopProver.make]] holds an `ES256` key, which is what both auth and edge allow by
  * default: RFC 9449 §5 mandates it, and `RS256` — verifiable, but off unless a deployment
  * opts in — is not accepted by either. [[DpopProver.rsa]] holds a `PS256` one, for the tests
  * that turn on what a client's registered key policy does to an RSA proof.
  */
final class DpopProver private (algorithm: JWSAlgorithm, signer: JWSSigner, publicJwk: JWK):

  /** The JWK thumbprint that ends up in a bound token's `cnf.jkt`. */
  val jkt: String = publicJwk.computeThumbprint().toString

  /** A proof for one request.
    *
    * `accessToken` adds the §4.2 `ath` binding, which a resource server requires and `/token`
    * has no token to compute one over; `nonce` answers a §9 challenge.
    */
  def proof(
      method: Method,
      uri: String,
      accessToken: Option[String] = None,
      nonce: Option[String] = None,
  ): Task[String] =
    ZIO.attempt:
      val header = JWSHeader.Builder(algorithm).`type`(DpopProver.JwtType).jwk(publicJwk).build()
      val claims = JWTClaimsSet.Builder()
        .claim("htm", method.name)
        .claim("htu", uri)
        .jwtID(UUID.randomUUID().toString)
        .issueTime(Date.from(Instant.now()))
      accessToken.foreach(token => claims.claim("ath", DpopProver.ath(token)))
      nonce.foreach(value => claims.claim("nonce", value))
      val jwt = SignedJWT(header, claims.build())
      jwt.sign(signer)
      jwt.serialize()

object DpopProver:

  /** §4.1: the `typ` every proof carries. */
  val JwtType: JOSEObjectType = JOSEObjectType("dpop+jwt")

  /** §9: the header a server hands a nonce back in, for the client to sign into its retry. */
  val NonceHeader = "DPoP-Nonce"

  def make: Task[DpopProver] =
    ZIO.attempt:
      val generator = java.security.KeyPairGenerator.getInstance("EC").nn
      generator.initialize(Curve.P_256.toECParameterSpec)
      val pair = generator.generateKeyPair().nn
      DpopProver(
        JWSAlgorithm.ES256,
        ECDSASigner(pair.getPrivate.asInstanceOf[ECPrivateKey]),
        ECKey.Builder(Curve.P_256, pair.getPublic.asInstanceOf[ECPublicKey]).build(),
      )

  /** A prover holding an RSA key of the given modulus length, signing `PS256`.
    *
    * `allowWeakKey` is passed to Nimbus's signer because it refuses to sign with a modulus
    * under 2048 bits — which is exactly the proof a test of the server's own floor has to be
    * able to produce. Nothing verifies with that concession; only signing does.
    */
  def rsa(keySize: Int = 2048): Task[DpopProver] =
    ZIO.attempt:
      val generator = java.security.KeyPairGenerator.getInstance("RSA").nn
      generator.initialize(keySize)
      val pair = generator.generateKeyPair().nn
      DpopProver(
        JWSAlgorithm.PS256,
        RSASSASigner(pair.getPrivate.asInstanceOf[RSAPrivateKey], true),
        RSAKey.Builder(pair.getPublic.asInstanceOf[RSAPublicKey]).build(),
      )

  /** The `DPoP-Nonce` a response carried, or `None` when it carried none. */
  def nonceOf(response: Response): Option[String] =
    response.rawHeader(NonceHeader)

  /** §4.2 — `base64url(SHA-256(access token))`, computed here rather than shared with the
    * server's own implementation, so that a regression in it cannot cancel itself out.
    */
  def ath(accessToken: String): String =
    Base64.getUrlEncoder.nn.withoutPadding().nn.encodeToString(
      MessageDigest.getInstance("SHA-256").nn.digest(accessToken.getBytes(StandardCharsets.US_ASCII)).nn,
    ).nn
