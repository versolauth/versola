package versola.util

import versola.auth.TestEnvConfig
import versola.oauth.model.{AuthorizationCode, RefreshToken}
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.security.SecureRandom as JSecureRandom

object AuthPropertyGeneratorSpec extends ZIOSpecDefault:

  /** Records the length requested of each call and returns a deterministic, distinct
    * fixed-length byte array (the byte value equals the requested length) so a test can
    * confirm both the length asked for and that the returned value actually wraps it.
    */
  private def fakeRandom(requestedLengths: Ref[List[Int]]): SecureRandom =
    new SecureRandom:
      override def nextBytes(length: Int): UIO[Array[Byte]] =
        requestedLengths.update(_ :+ length).as(Array.fill(length)(length.toByte))
      override def nextHex(length: Int): UIO[String] = ZIO.dieMessage("Unused in test")
      override def nextNumeric(length: Int): UIO[String] = ZIO.dieMessage("Unused in test")
      override def nextAlphanumeric(length: Int): UIO[String] = ZIO.dieMessage("Unused in test")
      override def nextUUIDv7: UIO[java.util.UUID] = ZIO.dieMessage("Unused in test")
      override def setSeed(seed: Long): UIO[Unit] = ZIO.dieMessage("Unused in test")
      override def execute[A](fn: JSecureRandom => A): UIO[A] = ZIO.dieMessage("Unused in test")

  def spec = suite("AuthPropertyGenerator")(
    // Not 16: the conformance suite's entropy estimate is taken from the encoded string and a
    // correct 128-bit code can fall under its floor (see AuthPropertyGenerator).
    test("nextAuthorizationCode draws 32 bytes") {
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths), TestEnvConfig.coreConfig)
        code <- generator.nextAuthorizationCode
        seen <- lengths.get
      yield assertTrue(seen == List(32), code.sameElements(Array.fill(32)(32.toByte)))
    },
    test("nextSessionId draws 32 bytes") {
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths), TestEnvConfig.coreConfig)
        id <- generator.nextSessionId
        seen <- lengths.get
      yield assertTrue(seen == List(32), id.sameElements(Array.fill(32)(32.toByte)))
    },
    test("nextPublicSessionId draws 16 bytes and base64url-encodes them") {
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths), TestEnvConfig.coreConfig)
        id <- generator.nextPublicSessionId
        seen <- lengths.get
      yield assertTrue(
        seen == List(16),
        (id: String) == versola.util.Base64.urlEncode(Array.fill(16)(16.toByte)),
      )
    },
    test("nextRefreshTokenFamilyId draws 16 bytes and base64url-encodes them") {
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths), TestEnvConfig.coreConfig)
        id <- generator.nextRefreshTokenFamilyId
        seen <- lengths.get
      yield assertTrue(
        seen == List(16),
        (id: String) == versola.util.Base64.urlEncode(Array.fill(16)(16.toByte)),
      )
    },
    test("nextAccessToken draws 16 bytes") {
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths), TestEnvConfig.coreConfig)
        token <- generator.nextAccessToken
        seen <- lengths.get
      yield assertTrue(seen == List(16), token.sameElements(Array.fill(16)(16.toByte)))
    },
    test("nextRefreshToken draws 32 bytes") {
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths), TestEnvConfig.coreConfig)
        token <- generator.nextRefreshToken
        seen <- lengths.get
      yield assertTrue(seen == List(32), token.sameElements(Array.fill(32)(32.toByte)))
    },
    // versolauth/versola#547 §6.2: the values a client presents back are issued under the
    // cell, keyed on the whole routed string; the ones it never presents are left alone.
    test("issues codes and refresh tokens under the configured cell, and nothing else") {
      val routed = s"v1.${TestEnvConfig.cellId}.${Base64.urlEncode(Array.fill(32)(32.toByte))}"
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths), TestEnvConfig.cellCoreConfig)
        code <- generator.nextAuthorizationCode
        token <- generator.nextRefreshToken
        sessionId <- generator.nextSessionId
        accessToken <- generator.nextAccessToken
      yield assertTrue(
        AuthorizationCode.present(code) == routed,
        code.sameElements(routed.getBytes(StandardCharsets.UTF_8)),
        RefreshToken.present(token) == routed,
        token.sameElements(routed.getBytes(StandardCharsets.UTF_8)),
        sessionId.sameElements(Array.fill(32)(32.toByte)),
        accessToken.sameElements(Array.fill(16)(16.toByte)),
      )
    },
    test("issues codes and refresh tokens as the bare random bytes when no cell is configured") {
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths), TestEnvConfig.coreConfig)
        code <- generator.nextAuthorizationCode
        token <- generator.nextRefreshToken
      yield assertTrue(
        AuthorizationCode.present(code) == Base64.urlEncode(Array.fill(32)(32.toByte)),
        RefreshToken.present(token) == Base64.urlEncode(Array.fill(32)(32.toByte)),
      )
    },
  )
