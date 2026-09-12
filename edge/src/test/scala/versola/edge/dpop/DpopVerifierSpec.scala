package versola.edge.dpop

import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.{Curve, ECKey}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.edge.{EdgeConfig, model}
import versola.util.{Base64, Dpop, DpopNonce, Secret}
import zio.*
import zio.http.{Header, Method, Path, Request, URL}
import zio.test.*

import java.security.MessageDigest
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.time.Instant
import java.util.Date

object DpopVerifierSpec extends ZIOSpecDefault:

  private val AccessToken = "header.payload.signature"
  private val RequestPath = Path.decode("/resources/users-api/users")
  private val PublicUrl = URL.decode("https://edge.example").toOption.get

  private val ecKeyPairGenerator = java.security.KeyPairGenerator.getInstance("EC").nn
  ecKeyPairGenerator.initialize(Curve.P_256.toECParameterSpec)
  private val ecKeyPair = ecKeyPairGenerator.generateKeyPair().nn
  private val ecPrivateKey = ecKeyPair.getPrivate.asInstanceOf[ECPrivateKey]
  private val ecJwk = ECKey.Builder(Curve.P_256, ecKeyPair.getPublic.asInstanceOf[ECPublicKey]).build()
  private val jkt = ecJwk.computeThumbprint().toString

  private val otherKeyPair =
    val generator = java.security.KeyPairGenerator.getInstance("EC").nn
    generator.initialize(Curve.P_256.toECParameterSpec)
    generator.generateKeyPair().nn
  private val otherJwk =
    ECKey.Builder(Curve.P_256, otherKeyPair.getPublic.asInstanceOf[ECPublicKey]).build()

  private val nonceSalt = Secret.Bytes32(Array.fill(32)(9.toByte))

  private def athOf(token: String): String =
    Base64.urlEncode(MessageDigest.getInstance("SHA-256").nn.digest(token.getBytes("US-ASCII")).nn)

  private def proof(
      htm: String = Method.GET.name,
      htu: String = s"${PublicUrl.encode}${RequestPath.encode}",
      jti: String = "jti-1",
      iat: Instant,
      ath: Option[String] = Some(athOf(AccessToken)),
      nonce: Option[String] = None,
      jwk: ECKey = ecJwk,
      signWith: ECPrivateKey = ecPrivateKey,
  ): String =
    val header = JWSHeader.Builder(JWSAlgorithm.ES256).`type`(Dpop.JwtType).jwk(jwk).build()
    val claims = JWTClaimsSet.Builder()
      .claim("htm", htm)
      .claim("htu", htu)
      .jwtID(jti)
      .issueTime(Date.from(iat))
    ath.foreach(claims.claim("ath", _))
    nonce.foreach(claims.claim("nonce", _))
    val jwt = SignedJWT(header, claims.build())
    jwt.sign(ECDSASigner(signWith))
    jwt.serialize()

  private def verifier(
      dpop: Option[EdgeConfig.Dpop] = Some(
        EdgeConfig.Dpop(publicUrl = PublicUrl, nonceSalt = nonceSalt),
      ),
  ): DpopVerifier =
    DpopVerifier.Impl(config(dpop), DpopReplayGuard.Impl())

  private def config(dpop: Option[EdgeConfig.Dpop]): EdgeConfig =
    val generator = java.security.KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    EdgeConfig(
      id = model.EdgeId("edge-1"),
      keyId = "kid-1",
      privateKey = generator.generateKeyPair().nn.getPrivate.nn,
      security = EdgeConfig.Security(
        tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
        edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
      ),
      central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
      versolaUrl = URL.decode("https://idp.example").toOption.get,
      configurationCacheRefreshInterval = 5.minutes,
      dpop = dpop,
    )

  private def verify(
      service: DpopVerifier,
      proofHeader: String,
      accessToken: String = AccessToken,
      boundKeyThumbprint: String = jkt,
      method: Method = Method.GET,
      path: Path = RequestPath,
  ) =
    service.verify(proofHeader, accessToken, boundKeyThumbprint, method, path).either

  def spec = suite("DpopVerifier")(
    test("accepts a proof bound to the presented token and the token's own key") {
      val service = verifier()
      for
        now <- Clock.instant
        result <- verify(service, proof(iat = now))
      yield assertTrue(result.map(_.jkt) == Right(jkt))
    },
    test("rejects a second use of the same proof") {
      val service = verifier()
      for
        now <- Clock.instant
        replayed = proof(iat = now)
        first <- verify(service, replayed)
        second <- verify(service, replayed)
      yield assertTrue(first.isRight, second == Left(DpopVerifier.Error.Replayed))
    },
    test("rejects a proof signed with a key the token was not bound to") {
      val service = verifier()
      for
        now <- Clock.instant
        result <- verify(
          service,
          proof(iat = now, jwk = otherJwk, signWith = otherKeyPair.getPrivate.asInstanceOf[ECPrivateKey]),
        )
      yield assertTrue(result == Left(DpopVerifier.Error.KeyMismatch))
    },
    test("rejects a proof whose ath names a different access token") {
      val service = verifier()
      for
        now <- Clock.instant
        result <- verify(service, proof(iat = now, ath = Some(athOf("some.other.token"))))
      yield assertTrue(result == Left(DpopVerifier.Error.AthMismatch))
    },
    test("rejects a proof carrying no ath at all") {
      val service = verifier()
      for
        now <- Clock.instant
        result <- verify(service, proof(iat = now, ath = None))
      yield assertTrue(result == Left(DpopVerifier.Error.AthMissing))
    },
    // The htu is built from the configured public URL, so a proof made over the address the
    // upstream or a forwarding hop knows this edge by does not validate.
    test("rejects a proof whose htu names an origin other than the configured public URL") {
      val service = verifier()
      for
        now <- Clock.instant
        result <- verify(service, proof(iat = now, htu = s"https://internal.local${RequestPath.encode}"))
      yield assertTrue(result == Left(DpopVerifier.Error.InvalidProof(Dpop.Error.UriMismatch)))
    },
    test("rejects a proof made for a different path on this edge") {
      val service = verifier()
      for
        now <- Clock.instant
        result <- verify(service, proof(iat = now), path = Path.decode("/resources/users-api/admins"))
      yield assertTrue(result == Left(DpopVerifier.Error.InvalidProof(Dpop.Error.UriMismatch)))
    },
    test("rejects a proof made for a different method") {
      val service = verifier()
      for
        now <- Clock.instant
        result <- verify(service, proof(iat = now), method = Method.DELETE)
      yield assertTrue(result == Left(DpopVerifier.Error.InvalidProof(Dpop.Error.MethodMismatch)))
    },
    test("refuses a DPoP request outright when no dpop block is configured") {
      val service = verifier(dpop = None)
      for
        now <- Clock.instant
        result <- verify(service, proof(iat = now))
      yield assertTrue(result == Left(DpopVerifier.Error.NotConfigured))
    },
    suite("nonce")(
      test("demands one, and supplies it, when the deployment requires a nonce") {
        val service = verifier(
          Some(EdgeConfig.Dpop(publicUrl = PublicUrl, nonceSalt = nonceSalt, requireNonce = true)),
        )
        for
          now <- Clock.instant
          result <- verify(service, proof(iat = now))
          issued = result.left.toOption.collect { case DpopVerifier.Error.NonceRequired(n) => n }
        yield assertTrue(
          issued.exists(DpopNonce.verify(nonceSalt, _, now, 300.seconds).isRight),
        )
      },
      test("accepts a proof carrying a nonce this edge issued") {
        val service = verifier(
          Some(EdgeConfig.Dpop(publicUrl = PublicUrl, nonceSalt = nonceSalt, requireNonce = true)),
        )
        for
          now <- Clock.instant
          result <- verify(service, proof(iat = now, nonce = Some(DpopNonce.issue(nonceSalt, now))))
        yield assertTrue(result.isRight)
      },
      test("rejects a nonce it never issued") {
        val service = verifier(
          Some(EdgeConfig.Dpop(publicUrl = PublicUrl, nonceSalt = nonceSalt, requireNonce = true)),
        )
        for
          now <- Clock.instant
          result <- verify(service, proof(iat = now, nonce = Some("not-mine")))
        yield assertTrue(result.left.toOption.exists(_.isInstanceOf[DpopVerifier.Error.NonceRequired]))
      },
      // §11.3: dropping the nonce after being challenged for one must not be a way back to
      // nonce-less proofs. Every refusal in this mode hands out a nonce, so a nonce-less
      // proof is, by construction, one from a client that has already been given one.
      test("refuses a nonce-less retry after the challenge that handed out a nonce") {
        val service = verifier(
          Some(EdgeConfig.Dpop(publicUrl = PublicUrl, nonceSalt = nonceSalt, requireNonce = true)),
        )
        for
          now <- Clock.instant
          challenged <- verify(service, proof(iat = now, jti = "proof-1"))
          retried <- verify(service, proof(iat = now, jti = "proof-2"))
        yield assertTrue(
          challenged.left.toOption.exists(_.isInstanceOf[DpopVerifier.Error.NonceRequired]),
          retried.left.toOption.exists(_.isInstanceOf[DpopVerifier.Error.NonceRequired]),
        )
      },
      // The other side of that rule: where nonces aren't in use, none is ever handed out, so
      // §11.3 never engages and a nonce this edge didn't issue carries no weight to check.
      test("ignores the nonce claim entirely when nonces aren't in use") {
        val service = verifier()
        for
          now <- Clock.instant
          result <- verify(service, proof(iat = now, nonce = Some("not-mine")))
        yield assertTrue(result.isRight)
      },
      test("accepts a proof with no nonce when the deployment doesn't require one") {
        val service = verifier()
        for
          now <- Clock.instant
          result <- verify(service, proof(iat = now))
        yield assertTrue(result.isRight)
      },
    ),
    // §4.3(1): "the request contains at most one DPoP header field value". `rawHeader`
    // answers with a single value regardless, so cardinality has to be checked separately
    // before any proof is read.
    suite("proofHeader")(
      test("reads the sole DPoP header") {
        val request = Request.get(URL.empty).addHeader(Header.Custom("DPoP", "proof-value"))
        assertZIO(DpopVerifier.proofHeader(request).either)(Assertion.equalTo(Right("proof-value")))
      },
      test("fails with ProofMissing when there is no DPoP header") {
        val request = Request.get(URL.empty)
        assertZIO(DpopVerifier.proofHeader(request).either)(
          Assertion.equalTo(Left(DpopVerifier.Error.ProofMissing)),
        )
      },
      test("fails with MultipleProofs rather than pick one, when the request carries two") {
        val request = Request.get(URL.empty)
          .addHeader(Header.Custom("DPoP", "proof-a"))
          .addHeader(Header.Custom("DPoP", "proof-b"))
        assertZIO(DpopVerifier.proofHeader(request).either)(
          Assertion.equalTo(Left(DpopVerifier.Error.MultipleProofs)),
        )
      },
    ),
  ) @@ TestAspect.silentLogging
