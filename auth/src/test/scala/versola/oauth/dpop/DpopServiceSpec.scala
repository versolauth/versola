package versola.oauth.dpop

import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.{Curve, ECKey}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.util.{Dpop, DpopNonce, UnitSpecBase}
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
    val configurationService = stub[OAuthConfigurationService]

    val service: DpopService = DpopService.Impl(proofRepository, nonceService, configurationService, config)

  /** §5.1: the accepted algorithms come from the metadata document, so every test has to say
    * what it advertises. Defaults to the set assumed for a document that stays silent. */
  private def makeEnv(algorithms: Set[Dpop.Algorithm] = Dpop.Algorithm.Default): UIO[Env] =
    val created = Env()
    created.configurationService.getDpopSigningAlgorithms.succeedsWith(algorithms).as(created)

  def spec = suite("DpopService")(
    test("returns the validated proof when it's fresh and no nonce is required") {
      for
        env <- makeEnv()
        now <- Clock.instant
        _ <- env.proofRepository.recordIfAbsent.succeedsWith(true)
        result <- env.service.verify(proof(iat = now), Htm, Htu, requireNonce = false).either
      yield assertTrue(result.map(_.jti) == Right("jti-1"))
    },
    // §5.1: the metadata document is the allow-list, so narrowing it has to narrow what is
    // actually accepted -- otherwise the field is decoration and a client can sign with
    // anything the build happens to implement.
    test("refuses a proof signed with an algorithm the metadata document doesn't advertise") {
      for
        env <- makeEnv(algorithms = Set(Dpop.Algorithm.PS256))
        now <- Clock.instant
        result <- env.service.verify(proof(iat = now), Htm, Htu, requireNonce = false).either
      yield assertTrue(
        result == Left(DpopService.Error.InvalidProof(Dpop.Error.UnsupportedAlgorithm)),
        env.proofRepository.recordIfAbsent.calls.isEmpty,
      )
    },
    test("accepts the same proof once the document advertises its algorithm") {
      for
        env <- makeEnv(algorithms = Set(Dpop.Algorithm.ES256))
        now <- Clock.instant
        _ <- env.proofRepository.recordIfAbsent.succeedsWith(true)
        result <- env.service.verify(proof(iat = now), Htm, Htu, requireNonce = false).either
      yield assertTrue(result.isRight)
    },
    test("fails with InvalidProof when the proof itself doesn't validate (e.g. wrong htm)") {
      for
        env <- makeEnv()
        now <- Clock.instant
        result <- env.service.verify(proof(htm = "GET", iat = now), Htm, Htu, requireNonce = false).either
      yield assertTrue(result == Left(DpopService.Error.InvalidProof(Dpop.Error.MethodMismatch)))
    },
    test("fails with Replayed when the (jkt, jti) pair was already recorded") {
      for
        env <- makeEnv()
        now <- Clock.instant
        _ <- env.proofRepository.recordIfAbsent.succeedsWith(false)
        result <- env.service.verify(proof(iat = now), Htm, Htu, requireNonce = false).either
      yield assertTrue(result == Left(DpopService.Error.Replayed))
    },
    test("fails with NonceRequired carrying a fresh nonce when one is required but absent") {
      for
        env <- makeEnv()
        now <- Clock.instant
        _ <- env.nonceService.issue.succeedsWith("fresh-nonce")
        result <- env.service.verify(proof(iat = now), Htm, Htu, requireNonce = true).either
      yield assertTrue(result == Left(DpopService.Error.NonceRequired("fresh-nonce")))
    },
    test("does not consult the repository at all when a required nonce is missing") {
      for
        env <- makeEnv()
        now <- Clock.instant
        _ <- env.nonceService.issue.succeedsWith("fresh-nonce")
        _ <- env.service.verify(proof(iat = now), Htm, Htu, requireNonce = true).either
      yield assertTrue(env.proofRepository.recordIfAbsent.calls.isEmpty)
    },
    test("proceeds past the nonce check when the proof carries a valid nonce") {
      for
        env <- makeEnv()
        now <- Clock.instant
        _ <- env.nonceService.verify.succeedsWith(())
        _ <- env.proofRepository.recordIfAbsent.succeedsWith(true)
        result <- env.service.verify(proof(iat = now, nonce = Some("srv-nonce")), Htm, Htu, requireNonce = true).either
      yield assertTrue(result.isRight)
    },
    test("fails with a fresh NonceRequired when the proof's nonce doesn't verify") {
      for
        env <- makeEnv()
        now <- Clock.instant
        _ <- env.nonceService.verify.failsWith(DpopNonce.Error.Expired)
        _ <- env.nonceService.issue.succeedsWith("fresh-nonce")
        result <- env.service.verify(proof(iat = now, nonce = Some("stale-nonce")), Htm, Htu, requireNonce = true).either
      yield assertTrue(result == Left(DpopService.Error.NonceRequired("fresh-nonce")))
    },
    // §11.3: dropping the nonce after being challenged for one must not be a way back to
    // nonce-less proofs. Every refusal in this mode hands out a nonce, so a nonce-less proof
    // is, by construction, one from a client that has already been given one.
    test("refuses a nonce-less retry after the challenge that handed out a nonce") {
      for
        env <- makeEnv()
        now <- Clock.instant
        _ <- env.nonceService.issue.succeedsWith("fresh-nonce")
        _ <- env.proofRepository.recordIfAbsent.succeedsWith(true)
        challenged <- env.service.verify(proof(iat = now), Htm, Htu, requireNonce = true).either
        retried <- env.service.verify(proof(iat = now), Htm, Htu, requireNonce = true).either
      yield assertTrue(
        challenged == Left(DpopService.Error.NonceRequired("fresh-nonce")),
        retried == Left(DpopService.Error.NonceRequired("fresh-nonce")),
      )
    },
    // The other side of that rule: an endpoint that doesn't require a nonce never hands one
    // out, so §11.3 never engages and there is nothing a present nonce could be checked
    // against that would carry any weight.
    test("ignores a present nonce entirely when the endpoint doesn't require one") {
      for
        env <- makeEnv()
        now <- Clock.instant
        _ <- env.proofRepository.recordIfAbsent.succeedsWith(true)
        result <- env.service.verify(proof(iat = now, nonce = Some("srv-nonce")), Htm, Htu, requireNonce = false).either
      yield assertTrue(env.nonceService.verify.calls.isEmpty, result.isRight)
    },
  )
