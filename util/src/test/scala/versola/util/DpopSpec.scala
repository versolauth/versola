package versola.util

import com.nimbusds.jose.crypto.{ECDSASigner, RSASSASigner}
import com.nimbusds.jose.jwk.{Curve, ECKey, OctetKeyPair, RSAKey}
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import zio.*
import zio.http.Method
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.{Base64, Date}
import javax.crypto.KeyGenerator

object DpopSpec extends ZIOSpecDefault:

  private val ecKeyPairGenerator = KeyPairGenerator.getInstance("EC")
  ecKeyPairGenerator.initialize(Curve.P_256.toECParameterSpec)
  private val ecKeyPair = ecKeyPairGenerator.generateKeyPair()
  private val ecPrivateKey = ecKeyPair.getPrivate.asInstanceOf[ECPrivateKey]
  private val ecPublicKey = ecKeyPair.getPublic.asInstanceOf[ECPublicKey]
  private val ecJwk = ECKey.Builder(Curve.P_256, ecPublicKey).build()

  private val otherEcKeyPair = ecKeyPairGenerator.generateKeyPair()
  private val otherEcPrivateKey = otherEcKeyPair.getPrivate.asInstanceOf[ECPrivateKey]

  private val rsaKeyPairGenerator = KeyPairGenerator.getInstance("RSA")
  rsaKeyPairGenerator.initialize(2048)
  private val rsaKeyPair = rsaKeyPairGenerator.generateKeyPair()
  private val rsaPrivateKey = rsaKeyPair.getPrivate.asInstanceOf[RSAPrivateKey]
  private val rsaPublicKey = rsaKeyPair.getPublic.asInstanceOf[RSAPublicKey]
  private val rsaJwk = RSAKey.Builder(rsaPublicKey).build()

  // A public key type Dpop.verifySignature doesn't special-case (neither RSAKey nor ECKey), to
  // exercise its fallback branch. Ed25519 rather than a symmetric key: Nimbus's own JWS header
  // parsing already refuses to round-trip a `jwk` carrying private/symmetric material (see the
  // "embedded private/symmetric key" test below), so a public OKP key is the only way to reach
  // that branch through `SignedJWT.parse` at all.
  private val okpJwk = OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(Array.fill[Byte](32)(9))).build()

  private val Htm = Method.POST
  private val Htu = "https://auth.example.com/token"
  private val AllAlgorithms = Set(Dpop.Algorithm.ES256, Dpop.Algorithm.PS256, Dpop.Algorithm.RS256)

  private def proof(
      alg: JWSAlgorithm = JWSAlgorithm.ES256,
      typ: JOSEObjectType = Dpop.JwtType,
      jwk: Option[com.nimbusds.jose.jwk.JWK] = Some(ecJwk),
      signer: com.nimbusds.jose.JWSSigner = ECDSASigner(ecPrivateKey),
      htm: String = Htm.name,
      htu: String = Htu,
      jti: String = "jti-1",
      iat: Instant = Instant.parse("2024-01-01T00:00:00Z"),
      nonce: Option[String] = None,
      ath: Option[String] = None,
  ): String =
    val headerBuilder = JWSHeader.Builder(alg).`type`(typ)
    jwk.foreach(headerBuilder.jwk)
    val claimsBuilder = JWTClaimsSet.Builder()
      .claim("htm", htm)
      .claim("htu", htu)
      .jwtID(jti)
      .issueTime(Date.from(iat))
    nonce.foreach(claimsBuilder.claim("nonce", _))
    ath.foreach(claimsBuilder.claim("ath", _))
    val jwt = SignedJWT(headerBuilder.build(), claimsBuilder.build())
    jwt.sign(signer)
    jwt.serialize()

  /** Hand-assembles a compact JWS from raw header/payload JSON, bypassing every Nimbus API that
    * would otherwise refuse to build it -- the only way to see what `Dpop.verify` does with a
    * proof whose header a well-behaved JOSE library could never produce in the first place.
    */
  private def rawEcToken(headerJson: String, claimsJson: String, signingKey: ECPrivateKey): String =
    def b64(bytes: Array[Byte]) = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    def b64s(s: String) = b64(s.getBytes("UTF-8"))
    val signingInput = s"${b64s(headerJson)}.${b64s(claimsJson)}".getBytes("UTF-8")
    val signature = java.security.Signature.getInstance("SHA256withECDSA")
    signature.initSign(signingKey)
    signature.update(signingInput)
    val jwsSignature = com.nimbusds.jose.crypto.impl.ECDSA.transcodeSignatureToConcat(
      signature.sign(),
      com.nimbusds.jose.crypto.impl.ECDSA.getSignatureByteArrayLength(JWSAlgorithm.ES256),
    )
    s"${b64s(headerJson)}.${b64s(claimsJson)}.${b64(jwsSignature)}"

  private val now = Instant.parse("2024-01-01T00:00:00Z")
  private val leeway = 60.seconds

  private def verify(token: String, allowed: Set[Dpop.Algorithm] = AllAlgorithms) =
    Dpop.verify(token, allowed, Htm, Htu, now, leeway)

  def spec = suite("Dpop")(
    test("accepts a well-formed ES256 proof and returns its claims") {
      for result <- verify(proof()).either
      yield assertTrue(
        result.map(_.jti) == Right("jti-1"),
        result.map(_.iat) == Right(now),
        result.toOption.exists(_.jkt == ecJwk.computeThumbprint().toString),
        result.toOption.exists(_.nonce.isEmpty),
        result.toOption.exists(_.ath.isEmpty),
      )
    },
    test("accepts a well-formed PS256 proof") {
      val token = proof(alg = JWSAlgorithm.PS256, jwk = Some(rsaJwk), signer = RSASSASigner(rsaPrivateKey))
      for result <- verify(token).either
      yield assertTrue(result.isRight)
    },
    test("accepts a well-formed RS256 proof") {
      val token = proof(alg = JWSAlgorithm.RS256, jwk = Some(rsaJwk), signer = RSASSASigner(rsaPrivateKey))
      for result <- verify(token).either
      yield assertTrue(result.isRight)
    },
    test("carries nonce and ath through when present") {
      val token = proof(nonce = Some("srv-nonce"), ath = Some("ath-value"))
      for result <- verify(token).either
      yield assertTrue(
        result.toOption.flatMap(_.nonce) == Some("srv-nonce"),
        result.toOption.flatMap(_.ath) == Some("ath-value"),
      )
    },
    test("rejects a proof that isn't a JWT at all") {
      for result <- verify("not-a-jwt").either
      yield assertTrue(result == Left(Dpop.Error.NotJWT))
    },
    test("rejects the wrong typ header") {
      val token = proof(typ = JOSEObjectType.JWT)
      for result <- verify(token).either
      yield assertTrue(result == Left(Dpop.Error.InvalidType))
    },
    test("rejects an algorithm outside the allowed set") {
      val token = proof()
      for result <- verify(token, allowed = Set(Dpop.Algorithm.PS256)).either
      yield assertTrue(result == Left(Dpop.Error.UnsupportedAlgorithm))
    },
    test("rejects HS256, which isn't a DPoP algorithm at all") {
      val hmacKey = KeyGenerator.getInstance("HmacSHA256").generateKey()
      val token = proof(
        alg = JWSAlgorithm.HS256,
        jwk = None,
        signer = com.nimbusds.jose.crypto.MACSigner(hmacKey),
      )
      for result <- verify(token).either
      yield assertTrue(result == Left(Dpop.Error.UnsupportedAlgorithm))
    },
    test("rejects a proof whose header embeds a private/symmetric key -- Nimbus refuses to " +
      "round-trip one, so this can only be hand-assembled, and surfaces as a parse failure") {
      val privateEcJwk = ECKey.Builder(Curve.P_256, ecPublicKey).privateKey(ecPrivateKey).build()
      val headerJson = s"""{"typ":"dpop+jwt","alg":"ES256","jwk":${privateEcJwk.toString}}"""
      val claimsJson = s"""{"htm":"${Htm.name}","htu":"$Htu","jti":"jti-1","iat":${now.getEpochSecond}}"""
      val token = rawEcToken(headerJson, claimsJson, ecPrivateKey)
      for result <- verify(token).either
      yield assertTrue(result == Left(Dpop.Error.NotJWT))
    },
    test("rejects a public key type it doesn't recognize as a DPoP signing key") {
      val token = proof(jwk = Some(okpJwk))
      for result <- verify(token).either
      yield assertTrue(result == Left(Dpop.Error.InvalidSignature))
    },
    test("rejects a proof missing the htm claim") {
      val header = JWSHeader.Builder(JWSAlgorithm.ES256).`type`(Dpop.JwtType).jwk(ecJwk).build()
      val claims = JWTClaimsSet.Builder()
        .claim("htu", Htu)
        .jwtID("jti-1")
        .issueTime(Date.from(now))
        .build()
      val jwt = SignedJWT(header, claims)
      jwt.sign(ECDSASigner(ecPrivateKey))
      for result <- verify(jwt.serialize()).either
      yield assertTrue(result == Left(Dpop.Error.MissingClaim("htm")))
    },
    test("rejects a proof signed by a different key than the one it embeds (key confusion)") {
      val token = proof(signer = ECDSASigner(otherEcPrivateKey))
      for result <- verify(token).either
      yield assertTrue(result == Left(Dpop.Error.InvalidSignature))
    },
    test("rejects a method (htm) that doesn't match the request") {
      val token = proof(htm = "GET")
      for result <- verify(token).either
      yield assertTrue(result == Left(Dpop.Error.MethodMismatch))
    },
    test("rejects a uri (htu) that doesn't match the request") {
      val token = proof(htu = "https://auth.example.com/other")
      for result <- verify(token).either
      yield assertTrue(result == Left(Dpop.Error.UriMismatch))
    },
    test("accepts an htu carrying a query string, ignoring it per RFC 9449 §4.3 step 9") {
      val token = proof(htu = s"$Htu?foo=bar")
      for result <- verify(token).either
      yield assertTrue(result.isRight)
    },
    test("accepts an htu carrying a fragment, ignoring it per RFC 9449 §4.3 step 9") {
      val token = proof(htu = s"$Htu#section")
      for result <- verify(token).either
      yield assertTrue(result.isRight)
    },
    test("still rejects a different path once the query string is stripped") {
      val token = proof(htu = "https://auth.example.com/other?foo=bar")
      for result <- verify(token).either
      yield assertTrue(result == Left(Dpop.Error.UriMismatch))
    },
    test("rejects an htu that is not a valid URI at all") {
      val token = proof(htu = "://not a uri")
      for result <- verify(token).either
      yield assertTrue(result == Left(Dpop.Error.UriMismatch))
    },
    test("rejects an iat too far in the past") {
      val token = proof(iat = now.minusSeconds(120))
      for result <- verify(token).either
      yield assertTrue(result == Left(Dpop.Error.IatOutOfWindow))
    },
    test("rejects an iat too far in the future") {
      val token = proof(iat = now.plusSeconds(120))
      for result <- verify(token).either
      yield assertTrue(result == Left(Dpop.Error.IatOutOfWindow))
    },
    test("accepts an iat right at the edge of the leeway window") {
      val token = proof(iat = now.minusSeconds(60))
      for result <- verify(token).either
      yield assertTrue(result.isRight)
    },
    test("rejects a proof whose payload isn't a JSON object, signed by an otherwise valid key -- " +
      "Nimbus only fails parsing the claims set lazily, so this can only be hand-assembled") {
      val headerJson = s"""{"typ":"dpop+jwt","alg":"ES256","jwk":${ecJwk.toString}}"""
      val claimsJson = "[1,2,3]"
      val raw = rawEcToken(headerJson, claimsJson, ecPrivateKey)
      for result <- verify(raw).either
      yield assertTrue(result == Left(Dpop.Error.NotJWT))
    },
    test("rejects a proof whose nonce claim isn't a string") {
      val headerJson = s"""{"typ":"dpop+jwt","alg":"ES256","jwk":${ecJwk.toString}}"""
      val claimsJson =
        s"""{"htm":"${Htm.name}","htu":"$Htu","jti":"jti-1","iat":${now.getEpochSecond},"nonce":{}}"""
      val raw = rawEcToken(headerJson, claimsJson, ecPrivateKey)
      for result <- verify(raw).either
      yield assertTrue(result == Left(Dpop.Error.MalformedClaim("nonce")))
    },
    test("rejects a proof whose ath claim isn't a string") {
      val headerJson = s"""{"typ":"dpop+jwt","alg":"ES256","jwk":${ecJwk.toString}}"""
      val claimsJson =
        s"""{"htm":"${Htm.name}","htu":"$Htu","jti":"jti-1","iat":${now.getEpochSecond},"ath":[]}"""
      val raw = rawEcToken(headerJson, claimsJson, ecPrivateKey)
      for result <- verify(raw).either
      yield assertTrue(result == Left(Dpop.Error.MalformedClaim("ath")))
    },
  )
