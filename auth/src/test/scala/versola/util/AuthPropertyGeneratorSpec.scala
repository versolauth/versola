package versola.util

import zio.*
import zio.test.*

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
    test("nextAuthorizationCode draws 16 bytes") {
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths))
        code <- generator.nextAuthorizationCode
        seen <- lengths.get
      yield assertTrue(seen == List(16), code.sameElements(Array.fill(16)(16.toByte)))
    },
    test("nextSessionId draws 32 bytes") {
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths))
        id <- generator.nextSessionId
        seen <- lengths.get
      yield assertTrue(seen == List(32), id.sameElements(Array.fill(32)(32.toByte)))
    },
    test("nextPublicSessionId draws 16 bytes and base64url-encodes them") {
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths))
        id <- generator.nextPublicSessionId
        seen <- lengths.get
      yield assertTrue(
        seen == List(16),
        (id: String) == versola.util.Base64.urlEncode(Array.fill(16)(16.toByte)),
      )
    },
    test("nextAccessToken draws 16 bytes") {
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths))
        token <- generator.nextAccessToken
        seen <- lengths.get
      yield assertTrue(seen == List(16), token.sameElements(Array.fill(16)(16.toByte)))
    },
    test("nextRefreshToken draws 32 bytes") {
      for
        lengths <- Ref.make(List.empty[Int])
        generator = AuthPropertyGenerator.Impl(fakeRandom(lengths))
        token <- generator.nextRefreshToken
        seen <- lengths.get
      yield assertTrue(seen == List(32), token.sameElements(Array.fill(32)(32.toByte)))
    },
  )
