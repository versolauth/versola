package versola.oauth.dpop

import versola.auth.TestEnvConfig
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

object DpopNonceServiceSpec extends UnitSpecBase:

  private val config = TestEnvConfig.coreConfig
  private val service = DpopNonceService.Impl(config)

  def spec = suite("DpopNonceService")(
    test("a freshly issued nonce verifies against the time it was issued") {
      for
        now <- Clock.instant
        nonce <- service.issue
        result <- service.verify(nonce, now).either
      yield assertTrue(result == Right(()))
    },
    test("verifies right up to the edge of the configured ttl") {
      for
        now <- Clock.instant
        nonce <- service.issue
        result <- service.verify(nonce, now.plus(config.dpopOrDefault.nonceTtl)).either
      yield assertTrue(result == Right(()))
    },
    test("fails once the ttl has elapsed") {
      for
        now <- Clock.instant
        nonce <- service.issue
        result <- service.verify(nonce, now.plus(config.dpopOrDefault.nonceTtl).plusSeconds(1)).either
      yield assertTrue(result == Left(DpopNonceService.Error.Expired))
    },
    test("fails for a nonce issued in the future relative to now (clock skew guard)") {
      for
        now <- Clock.instant
        nonce <- service.issue
        result <- service.verify(nonce, now.minusSeconds(1)).either
      yield assertTrue(result == Left(DpopNonceService.Error.Expired))
    },
    test("fails for a nonce with the right shape but a forged mac") {
      for
        now <- Clock.instant
        nonce <- service.issue
        tampered = nonce.split('.').nn.updated(1, "forged").mkString(".")
        result <- service.verify(tampered, now).either
      yield assertTrue(result == Left(DpopNonceService.Error.Malformed))
    },
    test("fails for a nonce with an unparseable timestamp") {
      for
        now <- Clock.instant
        result <- service.verify("not-a-number.abc", now).either
      yield assertTrue(result == Left(DpopNonceService.Error.Malformed))
    },
    test("fails for a nonce with no separator at all") {
      for
        now <- Clock.instant
        result <- service.verify("garbage", now).either
      yield assertTrue(result == Left(DpopNonceService.Error.Malformed))
    },
    test("fails when a mac is spliced onto a different nonce's timestamp") {
      for
        now <- Clock.instant
        nonceA <- service.issue
        _ <- TestClock.adjust(5.seconds)
        nonceB <- service.issue
        laterNow <- Clock.instant
        macOfA = nonceA.split('.').nn(1)
        timestampOfB = nonceB.split('.').nn(0)
        spliced = s"$timestampOfB.$macOfA"
        result <- service.verify(spliced, laterNow).either
      yield assertTrue(nonceA != nonceB, result == Left(DpopNonceService.Error.Malformed))
    },
  )
