package versola.oauth.dpop

import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.{Curve, ECKey}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.auth.TestEnvConfig
import versola.util.{Dpop, UnitSpecBase}
import zio.*
import zio.http.Method
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.time.Instant
import java.util.Date

object DpopServiceSpec extends UnitSpecBase:

  private val config = TestEnvConfig.coreConfig

  private val ecKeyPairGenerator = KeyPairGenerator.getInstance("EC")
  ecKeyPairGenerator.initialize(Curve.P_256.toECParameterSpec)
  private val ecKeyPair = ecKeyPairGenerator.generateKeyPair()
  private val ecPrivateKey = ecKeyPair.getPrivate.asInstanceOf[ECPrivateKey]
  private val ecPublicKey = ecKeyPair.getPublic.asInstanceOf[ECPublicKey]
  private val ecJwk = ECKey.Builder(Curve.P_256, ecPublicKey).build()

  private val Htm = Method.POST
  private val Htu = "https://auth.example.com/token"

  private def proof(
      htm: String = Htm.name,
      htu: String = Htu,
      jti: String = "jti-1",
      iat: Instant = Instant.now,
      nonce: Option[String] = None,
  ): String =
    val header = JWSHeader.Builder(JWSAlgorithm.ES256).`type`(Dpop.JwtType).jwk(ecJwk).build()
    val claimsBuilder = JWTClaimsSet.Builder()
      .claim("htm", htm)
      .claim("htu", htu)
      .jwtID(jti)
      .issueTime(Date.from(iat))
    nonce.foreach(claimsBuilder.claim("nonce", _))
    val jwt = SignedJWT(header, claimsBuilder.build())
    jwt.sign(ECDSASigner(ecPrivateKey))
    jwt.serialize()

  private class Env:
    val proofRepository = stub[DpopProofRepository]
    val nonceService = stub[DpopNonceService]

    val service: DpopService = DpopService.Impl(proofRepository, nonceService, config)

  def spec = suite("DpopService")(
    test("returns the validated proof when it's fresh and no nonce is required") {
      val env = Env()
      for
        now <- Clock.instant
        _ <- env.proofRepository.recordIfAbsent.succeedsWith(true)
        result <- env.service.verify(proof(iat = now), Htm, Htu, requireNonce = false).either
      yield assertTrue(result.map(_.jti) == Right("jti-1"))
    },
    test("fails with InvalidProof when the proof itself doesn't validate (e.g. wrong htm)") {
      val env = Env()
      for
        now <- Clock.instant
        result <- env.service.verify(proof(htm = "GET", iat = now), Htm, Htu, requireNonce = false).either
      yield assertTrue(result == Left(DpopService.Error.InvalidProof(Dpop.Error.MethodMismatch)))
    },
    test("fails with Replayed when the (jkt, jti) pair was already recorded") {
      val env = Env()
      for
        now <- Clock.instant
        _ <- env.proofRepository.recordIfAbsent.succeedsWith(false)
        result <- env.service.verify(proof(iat = now), Htm, Htu, requireNonce = false).either
      yield assertTrue(result == Left(DpopService.Error.Replayed))
    },
    test("fails with NonceRequired carrying a fresh nonce when one is required but absent") {
      val env = Env()
      for
        now <- Clock.instant
        _ <- env.nonceService.issue.succeedsWith("fresh-nonce")
        result <- env.service.verify(proof(iat = now), Htm, Htu, requireNonce = true).either
      yield assertTrue(result == Left(DpopService.Error.NonceRequired("fresh-nonce")))
    },
    test("does not consult the repository at all when a required nonce is missing") {
      val env = Env()
      for
        now <- Clock.instant
        _ <- env.nonceService.issue.succeedsWith("fresh-nonce")
        _ <- env.service.verify(proof(iat = now), Htm, Htu, requireNonce = true).either
      yield assertTrue(env.proofRepository.recordIfAbsent.calls.isEmpty)
    },
    test("proceeds past the nonce check when the proof carries a valid nonce") {
      val env = Env()
      for
        now <- Clock.instant
        _ <- env.nonceService.verify.succeedsWith(())
        _ <- env.proofRepository.recordIfAbsent.succeedsWith(true)
        result <- env.service.verify(proof(iat = now, nonce = Some("srv-nonce")), Htm, Htu, requireNonce = true).either
      yield assertTrue(result.isRight)
    },
    test("fails with a fresh NonceRequired when the proof's nonce doesn't verify") {
      val env = Env()
      for
        now <- Clock.instant
        _ <- env.nonceService.verify.failsWith(DpopNonceService.Error.Expired)
        _ <- env.nonceService.issue.succeedsWith("fresh-nonce")
        result <- env.service.verify(proof(iat = now, nonce = Some("stale-nonce")), Htm, Htu, requireNonce = true).either
      yield assertTrue(result == Left(DpopService.Error.NonceRequired("fresh-nonce")))
    },
    test("still validates a present nonce even when the endpoint doesn't require one") {
      val env = Env()
      for
        now <- Clock.instant
        _ <- env.nonceService.verify.succeedsWith(())
        _ <- env.proofRepository.recordIfAbsent.succeedsWith(true)
        result <- env.service.verify(proof(iat = now, nonce = Some("srv-nonce")), Htm, Htu, requireNonce = false).either
      yield assertTrue(env.nonceService.verify.calls.nonEmpty, result.isRight)
    },
  )
