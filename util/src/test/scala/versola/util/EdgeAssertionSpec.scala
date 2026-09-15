package versola.util

import com.nimbusds.jose.jwk.RSAKey
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

object EdgeAssertionSpec extends ZIOSpecDefault:

  private val generator = KeyPairGenerator.getInstance("RSA")
  generator.initialize(2048)

  private val edgeKeyPair = generator.generateKeyPair()
  private val edgePrivateKey = edgeKeyPair.getPrivate.asInstanceOf[RSAPrivateKey]
  private val edgePublicKey = edgeKeyPair.getPublic.asInstanceOf[RSAPublicKey]

  private val otherKeyPair = generator.generateKeyPair()
  private val otherPrivateKey = otherKeyPair.getPrivate.asInstanceOf[RSAPrivateKey]

  private val edgeId = "edge-1"
  private val keyId = "kid-1"
  private val accessToken = "bound-access-token"

  /** The key set central serves for this edge: one JWK, keyed by the same `kid` the edge signs
    * with (`EdgeService.registerEdge` issues the pair, so the two always agree). */
  private def keySet(publicKey: RSAPublicKey, kid: String = keyId): JWT.PublicKeys =
    val jwk = RSAKey.Builder(publicKey).keyID(kid).build().toJSONString
    JWT.PublicKeys.fromJson(
      Json.Obj("keys" -> Json.Arr(jwk.fromJson[Json.Obj].getOrElse(Json.Obj()))),
    )

  private val edgeKeys = keySet(edgePublicKey)

  def spec = suite("EdgeAssertion")(
    test("verifies an assertion the named edge signed for the token in hand") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, edgePrivateKey, accessToken)
        id <- EdgeAssertion.edgeIdOf(assertion)
        result <- EdgeAssertion.verify(assertion, edgeKeys, accessToken).either
      yield assertTrue(id == edgeId, result.map(_.jti.nonEmpty).contains(true))
    },
    test("refuses an assertion made for a different access token") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, edgePrivateKey, accessToken)
        result <- EdgeAssertion.verify(assertion, edgeKeys, "some-other-access-token").flip
      yield assertTrue(result == EdgeAssertion.Error.TokenMismatch)
    },
    test("refuses an assertion signed with a key this edge did not register") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, otherPrivateKey, accessToken)
        result <- EdgeAssertion.verify(assertion, edgeKeys, accessToken).flip
      yield assertTrue(result == EdgeAssertion.Error.Unsigned)
    },
    test("refuses an assertion whose kid names no key in the set") {
      for
        assertion <- EdgeAssertion.issue(edgeId, "some-other-kid", edgePrivateKey, accessToken)
        result <- EdgeAssertion.verify(assertion, edgeKeys, accessToken).flip
      yield assertTrue(result == EdgeAssertion.Error.Unsigned)
    },
    test("refuses an assertion once its ttl has run out") {
      for
        assertion <- EdgeAssertion.issue(edgeId, keyId, edgePrivateKey, accessToken)
        _ <- TestClock.adjust(EdgeAssertion.Ttl.plus(1.second))
        result <- EdgeAssertion.verify(assertion, edgeKeys, accessToken).flip
      yield assertTrue(result == EdgeAssertion.Error.Expired)
    },
    // The edge key that signs an assertion also signs the sync token edge sends central every
    // few minutes: same key, same `edge_id` header, same JWT type. Only the audience and the
    // `ath` claim tell the two apart, so a sync token must not read as an assertion here --
    // otherwise anything that has seen one could use it to strip DPoP off a bound token.
    test("refuses a token the edge minted for central rather than for auth") {
      for
        centralSyncToken <- JWT.serialize(
          claims = JWT.Claims(
            issuer = "edge",
            subject = "edge",
            audience = List("central"),
            custom = Json.Obj("ath" -> Json.Str(Dpop.ath(accessToken))),
          ),
          ttl = 10.minutes,
          signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, keyId, edgePrivateKey),
          headers = Map(EdgeAssertion.EdgeIdHeader -> edgeId),
        )
        result <- EdgeAssertion.verify(centralSyncToken, edgeKeys, accessToken).flip
      yield assertTrue(result == EdgeAssertion.Error.WrongAudience)
    },
    // JWT.deserialize treats a token with no `exp` as unexpired, so the claim is required
    // here -- otherwise a signer could mint an assertion that never stops being valid.
    test("refuses an assertion carrying no expiry at all") {
      for
        noExpiry <- ZIO.attempt {
          import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
          import com.nimbusds.jose.crypto.RSASSASigner
          import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
          val claims = JWTClaimsSet.Builder()
            .jwtID("assertion-without-expiry")
            .issuer(edgeId)
            .subject(edgeId)
            .audience(java.util.List.of(EdgeAssertion.Audience))
            .claim("ath", Dpop.ath(accessToken))
            .build()
          val header = JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(keyId)
            .`type`(JOSEObjectType.JWT)
            .customParam(EdgeAssertion.EdgeIdHeader, edgeId)
            .build()
          val jwt = SignedJWT(header, claims)
          jwt.sign(RSASSASigner(edgePrivateKey))
          jwt.serialize()
        }
        result <- EdgeAssertion.verify(noExpiry, edgeKeys, accessToken).flip
      yield assertTrue(result == EdgeAssertion.Error.Malformed)
    },
    test("refuses a token carrying no ath claim at all") {
      for
        noAth <- JWT.serialize(
          claims = JWT.Claims(
            issuer = edgeId,
            subject = edgeId,
            audience = List(EdgeAssertion.Audience),
            custom = Json.Obj(),
          ),
          ttl = EdgeAssertion.Ttl,
          signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, keyId, edgePrivateKey),
          headers = Map(EdgeAssertion.EdgeIdHeader -> edgeId),
        )
        result <- EdgeAssertion.verify(noAth, edgeKeys, accessToken).flip
      yield assertTrue(result == EdgeAssertion.Error.Malformed)
    },
    test("reports a malformed edge id rather than reading one off an unsigned assertion") {
      for
        missing <- JWT.serialize(
          claims = JWT.Claims(edgeId, edgeId, List(EdgeAssertion.Audience), Json.Obj()),
          ttl = EdgeAssertion.Ttl,
          signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, keyId, edgePrivateKey),
        )
        result <- EdgeAssertion.edgeIdOf(missing).flip
        garbage <- EdgeAssertion.edgeIdOf("not-a-jwt").flip
      yield assertTrue(
        result == EdgeAssertion.Error.Malformed,
        garbage == EdgeAssertion.Error.Malformed,
      )
    },
  ) @@ TestAspect.silentLogging
