package versola.util

import com.nimbusds.jose.crypto.{ECDSASigner, MACSigner, RSASSASigner}
import com.nimbusds.jose.jwk.{Curve, ECKey, JWKSet, KeyUse, RSAKey}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader, JWSSigner}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import zio.*
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.Date
import scala.jdk.CollectionConverters.*

object ClientAssertionSpec extends ZIOSpecDefault:

  private val ecKeyPairGenerator = KeyPairGenerator.getInstance("EC")
  ecKeyPairGenerator.initialize(Curve.P_256.toECParameterSpec)
  private val ecKeyPair = ecKeyPairGenerator.generateKeyPair()
  private val ecPrivateKey = ecKeyPair.getPrivate.asInstanceOf[ECPrivateKey]
  private val ecPublicKey = ecKeyPair.getPublic.asInstanceOf[ECPublicKey]
  private val ecJwk = ECKey.Builder(Curve.P_256, ecPublicKey).keyID("ec-1").build()

  private val otherEcKeyPair = ecKeyPairGenerator.generateKeyPair()
  private val otherEcPrivateKey = otherEcKeyPair.getPrivate.asInstanceOf[ECPrivateKey]
  private val otherEcJwk =
    ECKey.Builder(Curve.P_256, otherEcKeyPair.getPublic.asInstanceOf[ECPublicKey]).keyID("ec-2").build()

  private val rsaKeyPairGenerator = KeyPairGenerator.getInstance("RSA")
  rsaKeyPairGenerator.initialize(2048)
  private val rsaKeyPair = rsaKeyPairGenerator.generateKeyPair()
  private val rsaPrivateKey = rsaKeyPair.getPrivate.asInstanceOf[RSAPrivateKey]
  private val rsaJwk =
    RSAKey.Builder(rsaKeyPair.getPublic.asInstanceOf[RSAPublicKey]).keyID("rsa-1").build()

  private val ClientId = "client-1"
  private val Issuer = "https://auth.example.com"
  private val TokenEndpoint = "https://auth.example.com/token"
  private val Fapi2 = JwtAudience.IssuerOnly(Issuer)
  private val Standard = JwtAudience.AnyOf(Set(Issuer, TokenEndpoint))

  private val now = Instant.parse("2024-01-01T00:00:00Z")
  private val maxLifetime = 5.minutes

  private def keys(jwks: com.nimbusds.jose.jwk.JWK*): JWT.PublicKeys =
    JWT.PublicKeys(JWKSet(jwks.toList.asJava))

  private def assertion(
      alg: JWSAlgorithm = JWSAlgorithm.ES256,
      signer: JWSSigner = ECDSASigner(ecPrivateKey),
      kid: Option[String] = Some("ec-1"),
      iss: Option[String] = Some(ClientId),
      sub: Option[String] = Some(ClientId),
      aud: List[String] = List(Issuer),
      audArray: Boolean = false,
      jti: Option[String] = Some("jti-1"),
      exp: Option[Instant] = Some(now.plusSeconds(60)),
      nbf: Option[Instant] = None,
      iat: Option[Instant] = None,
  ): String =
    val headerBuilder = JWSHeader.Builder(alg)
    kid.foreach(headerBuilder.keyID)
    val claimsBuilder = JWTClaimsSet.Builder()
    iss.foreach(claimsBuilder.issuer)
    sub.foreach(claimsBuilder.subject)
    if aud.nonEmpty && !audArray then claimsBuilder.audience(aud.asJava)
    jti.foreach(claimsBuilder.jwtID)
    exp.foreach(instant => claimsBuilder.expirationTime(Date.from(instant)))
    nbf.foreach(instant => claimsBuilder.notBeforeTime(Date.from(instant)))
    iat.foreach(instant => claimsBuilder.issueTime(Date.from(instant)))
    if audArray then
      // Nimbus collapses a one-element `aud` list into a string when it serializes a claims
      // set, so an array is written into the raw payload instead.
      val payload = claimsBuilder.build().toJSONObject
      payload.put("aud", java.util.ArrayList(aud.asJava))
      val jws = com.nimbusds.jose.JWSObject(headerBuilder.build(), com.nimbusds.jose.Payload(payload))
      jws.sign(signer)
      jws.serialize()
    else
      val jwt = SignedJWT(headerBuilder.build(), claimsBuilder.build())
      jwt.sign(signer)
      jwt.serialize()

  private val AllAlgorithms = ClientAssertion.Algorithm.values.toSet

  private def verify(
      token: String,
      publicKeys: JWT.PublicKeys = keys(ecJwk),
      clientId: String = ClientId,
      allowedAlgorithms: Set[ClientAssertion.Algorithm] = AllAlgorithms,
      audience: JwtAudience = Fapi2,
  ) =
    ClientAssertion.verify(token, publicKeys, allowedAlgorithms, clientId, audience, now, maxLifetime)

  def spec = suite("ClientAssertion")(
    suite("verify")(
      test("accepts a well-formed ES256 assertion and returns the claims replay needs") {
        for result <- verify(assertion()).either
        yield assertTrue(
          result.map(_.jti) == Right("jti-1"),
          result.map(_.expiresAt) == Right(now.plusSeconds(60)),
        )
      },
      test("accepts PS256 and RS256 assertions signed with a registered RSA key") {
        for
          ps256 <- verify(
            assertion(alg = JWSAlgorithm.PS256, signer = RSASSASigner(rsaPrivateKey), kid = Some("rsa-1")),
            keys(rsaJwk),
          ).either
          rs256 <- verify(
            assertion(alg = JWSAlgorithm.RS256, signer = RSASSASigner(rsaPrivateKey), kid = Some("rsa-1")),
            keys(rsaJwk),
          ).either
        yield assertTrue(ps256.isRight, rs256.isRight)
      },
      test("FAPI 2.0 §5.3.2.1-8: accepts the issuer identifier as a string") {
        for result <- verify(assertion(aud = List(Issuer))).either
        yield assertTrue(result.isRight)
      },
      test("FAPI 2.0 §5.3.2.1-8: rejects an endpoint URL as the audience") {
        for result <- verify(assertion(aud = List(TokenEndpoint))).either
        yield assertTrue(result == Left(ClientAssertion.Error.AudienceMismatch))
      },
      test("FAPI 2.0 §5.3.2.1-8: rejects an array, even one naming only the issuer") {
        for
          alone <- verify(assertion(aud = List(Issuer), audArray = true)).either
          withOthers <- verify(assertion(aud = List(Issuer, "https://elsewhere.example"), audArray = true)).either
        yield assertTrue(
          alone == Left(ClientAssertion.Error.AudienceMismatch),
          withOthers == Left(ClientAssertion.Error.AudienceMismatch),
        )
      },
      test("a standard-profile tenant still accepts the endpoint URL, or an array naming an accepted value") {
        for
          endpoint <- verify(assertion(aud = List(TokenEndpoint)), audience = Standard).either
          array <- verify(
            assertion(aud = List("https://elsewhere.example", TokenEndpoint), audArray = true),
            audience = Standard,
          ).either
        yield assertTrue(endpoint.isRight, array.isRight)
      },
      test("rejects an audience naming neither the issuer nor an endpoint") {
        for
          fapi2 <- verify(assertion(aud = List("https://elsewhere.example"))).either
          standard <- verify(assertion(aud = List("https://elsewhere.example")), audience = Standard).either
        yield assertTrue(
          fapi2 == Left(ClientAssertion.Error.AudienceMismatch),
          standard == Left(ClientAssertion.Error.AudienceMismatch),
        )
      },
      test("an assertion minted by issue() passes the FAPI 2.0 audience rule") {
        for
          token <- ClientAssertion.issue(ClientId, Issuer, ClientAssertion.Algorithm.ES256, "ec-1", ecPrivateKey)
          issuedAt <- Clock.instant
          result <- ClientAssertion.verify(token, keys(ecJwk), AllAlgorithms, ClientId, Fapi2, issuedAt, maxLifetime).either
        yield assertTrue(result.isRight)
      },
      test("rejects an assertion whose iss or sub is not the client being authenticated") {
        for
          wrongIss <- verify(assertion(iss = Some("other-client"))).either
          wrongSub <- verify(assertion(sub = Some("other-client"))).either
        yield assertTrue(
          wrongIss == Left(ClientAssertion.Error.IssuerMismatch),
          wrongSub == Left(ClientAssertion.Error.IssuerMismatch),
        )
      },
      test("rejects an assertion signed by a key the client did not register") {
        for result <- verify(assertion(kid = None, signer = ECDSASigner(otherEcPrivateKey))).either
        yield assertTrue(result == Left(ClientAssertion.Error.InvalidSignature))
      },
      test("rejects a kid naming no registered key rather than trying the others") {
        val token = assertion(kid = Some("unknown"))
        for result <- verify(token, keys(ecJwk, otherEcJwk)).either
        yield assertTrue(result == Left(ClientAssertion.Error.UnknownKey))
      },
      test("finds the right key by kid when several are registered") {
        for result <- verify(assertion(kid = Some("ec-1")), keys(otherEcJwk, ecJwk)).either
        yield assertTrue(result.isRight)
      },
      test("tries every usable key when the assertion carries no kid") {
        for result <- verify(assertion(kid = None), keys(otherEcJwk, ecJwk)).either
        yield assertTrue(result.isRight)
      },
      test("rejects an algorithm the deployment does not advertise") {
        for result <- verify(assertion(), allowedAlgorithms = Set(ClientAssertion.Algorithm.PS256)).either
        yield assertTrue(result == Left(ClientAssertion.Error.UnsupportedAlgorithm))
      },
      test("rejects an HMAC-signed assertion, which is client_secret_jwt and not this method") {
        val secret = Array.fill[Byte](32)(7)
        val token = assertion(alg = JWSAlgorithm.HS256, signer = MACSigner(secret), kid = None)
        for result <- verify(token).either
        yield assertTrue(result == Left(ClientAssertion.Error.UnsupportedAlgorithm))
      },
      test("rejects a key pinned by `alg` to a different algorithm") {
        val pinned = ECKey.Builder(Curve.P_256, ecPublicKey).keyID("ec-1").algorithm(JWSAlgorithm.ES384).build()
        for result <- verify(assertion(), keys(pinned)).either
        yield assertTrue(result == Left(ClientAssertion.Error.UnknownKey))
      },
      test("rejects a key registered for encryption") {
        val encryptionKey =
          ECKey.Builder(Curve.P_256, ecPublicKey).keyID("ec-1").keyUse(KeyUse.ENCRYPTION).build()
        for result <- verify(assertion(), keys(encryptionKey)).either
        yield assertTrue(result == Left(ClientAssertion.Error.UnknownKey))
      },
      test("rejects an expired assertion") {
        for result <- verify(assertion(exp = Some(now.minusSeconds(1)))).either
        yield assertTrue(result == Left(ClientAssertion.Error.Expired))
      },
      test("rejects an exp further out than the lifetime cap, which bounds replay storage") {
        for result <- verify(assertion(exp = Some(now.plusSeconds(600)))).either
        yield assertTrue(result == Left(ClientAssertion.Error.LifetimeTooLong))
      },
      test("FAPI 2.0 §5.3.2.1-13: accepts nbf/iat up to 60s in the future, refuses them beyond") {
        def at(seconds: Long) = Some(now.plusSeconds(seconds))
        for
          nbf9 <- verify(assertion(nbf = at(9))).either
          nbf10 <- verify(assertion(nbf = at(10))).either
          nbf11 <- verify(assertion(nbf = at(11))).either
          nbf60 <- verify(assertion(nbf = at(60), exp = at(120))).either
          nbf61 <- verify(assertion(nbf = at(61), exp = at(120))).either
          iat10 <- verify(assertion(iat = at(10))).either
          iat60 <- verify(assertion(iat = at(60), exp = at(120))).either
          iat61 <- verify(assertion(iat = at(61), exp = at(120))).either
        yield assertTrue(
          nbf9.isRight,
          nbf10.isRight,
          nbf11.isRight,
          nbf60.isRight,
          nbf61 == Left(ClientAssertion.Error.NotYetValid),
          iat10.isRight,
          iat60.isRight,
          iat61 == Left(ClientAssertion.Error.IssuedInFuture),
        )
      },
      test("accepts an iat in the past") {
        for result <- verify(assertion(iat = Some(now.minusSeconds(30)))).either
        yield assertTrue(result.isRight)
      },
      test("requires the claims replay protection and expiry depend on") {
        for
          noJti <- verify(assertion(jti = None)).either
          noExp <- verify(assertion(exp = None)).either
          noIss <- verify(assertion(iss = None)).either
          noSub <- verify(assertion(sub = None)).either
          noAud <- verify(assertion(aud = Nil)).either
        yield assertTrue(
          noJti == Left(ClientAssertion.Error.MissingClaim("jti")),
          noExp == Left(ClientAssertion.Error.MissingClaim("exp")),
          noIss == Left(ClientAssertion.Error.MissingClaim("iss")),
          noSub == Left(ClientAssertion.Error.MissingClaim("sub")),
          noAud == Left(ClientAssertion.Error.MissingClaim("aud")),
        )
      },
      test("rejects something that is not a JWS at all") {
        for result <- verify("not-a-jwt").either
        yield assertTrue(result == Left(ClientAssertion.Error.NotJWT))
      },
    ),
    suite("Algorithm.fromMetadata")(
      test("reads the advertised set off the metadata document") {
        val document = """{"token_endpoint_auth_signing_alg_values_supported":["RS256"]}"""
        for parsed <- ZIO.fromEither(zio.json.ast.Json.decoder.decodeJson(document))
        yield assertTrue(
          ClientAssertion.Algorithm.fromMetadata(parsed.asInstanceOf[zio.json.ast.Json.Obj]) ==
            Set(ClientAssertion.Algorithm.RS256),
        )
      },
      test("falls back only where the document does not name the field") {
        for
          empty <- ZIO.fromEither(zio.json.ast.Json.decoder.decodeJson("{}"))
          unknown <- ZIO.fromEither(
            zio.json.ast.Json.decoder.decodeJson(
              """{"token_endpoint_auth_signing_alg_values_supported":["EdDSA"]}""",
            ),
          )
        yield assertTrue(
          ClientAssertion.Algorithm.fromMetadata(empty.asInstanceOf[zio.json.ast.Json.Obj]) ==
            ClientAssertion.Algorithm.Default,
          ClientAssertion.Algorithm.fromMetadata(unknown.asInstanceOf[zio.json.ast.Json.Obj]).isEmpty,
        )
      },
    ),
  )
