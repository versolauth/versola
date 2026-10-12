package versola.util

import zio.config.typesafe.TypesafeConfigProvider
import zio.test.*
import zio.{Config, UIO}

/** What every service's secret decoders have to satisfy, run against the service's own `DeriveConfig` givens.
  *
  * The secret under test is a recognisable marker, so a leak shows up as the marker (or a piece of it) in the rendered error: in its
  * `toString`, in its message, and in the `toString` and message of every exception in its `cause` chain.
  */
object SecretConfigChecks:

  /** Valid base64url, 22 characters: 16 bytes once decoded. */
  val marker = "Zq9Xk7LEAKmarkerP4wN2v"

  /** 44 characters, 33 bytes once decoded: the wrong length for both 16 and 32. */
  val longMarker = marker + marker

  /** The config path the secret is read from. zio-config puts it into the error, which is how the name of the secret gets there. */
  val name = "refresh-tokens-secret"

  /** The marker and its head and tail: a leak of only a prefix or a suffix is a leak too. */
  val pieces = List(marker, marker.take(8), marker.takeRight(8))

  /** What `java.util.Base64` writes about an illegal character; it carries the character's code. */
  private val illegalCharacter = List("Illegal base64")

  private val paddedBytes32 = java.util.Base64.getUrlEncoder.encodeToString(Array.fill(32)(7.toByte))
  private val unpaddedBytes32 = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(Array.fill(32)(7.toByte))
  private val paddedBytes16 = java.util.Base64.getUrlEncoder.encodeToString(Array.fill(16)(7.toByte))
  private val unpaddedBytes16 = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(Array.fill(16)(7.toByte))

  def decoders(
      bytes16: Option[Config[Secret.Bytes16]],
      bytes32: Config[Secret.Bytes32],
      plain: Config[Secret],
      aesKey: Option[Config[javax.crypto.SecretKey]],
      privateKey: Option[Config[java.security.PrivateKey]],
  ): Spec[Any, Nothing] =
    val common = List(
      test("Bytes32: a wrong length names the expected and the actual length and nothing else") {
        rejects(bytes32, marker, List("expected 32 bytes", "got 16"))
      },
      test("Bytes32: an illegal character does not leak through the decoder's message") {
        rejects(bytes32, marker + "+/", List("not valid base64url"), illegalCharacter)
      },
      test("Bytes32: padded and unpadded values are both accepted") {
        for
          padded <- decodesTo(bytes32, paddedBytes32, 32)
          unpadded <- decodesTo(bytes32, unpaddedBytes32, 32)
        yield padded && unpadded
      },
      test("Secret: an illegal character does not leak through the decoder's message") {
        rejects(plain, marker + "+/", List("not valid base64url"), illegalCharacter)
      },
    )
    val sixteen = bytes16.toList.flatMap: config =>
      List(
        test("Bytes16: a wrong length names the expected and the actual length and nothing else") {
          rejects(config, longMarker, List("expected 16 bytes", "got 33"))
        },
        test("Bytes16: an illegal character does not leak through the decoder's message") {
          rejects(config, marker + "+/", List("not valid base64url"), illegalCharacter)
        },
        test("Bytes16: padded and unpadded values are both accepted") {
          for
            padded <- decodesTo(config, paddedBytes16, 16)
            unpadded <- decodesTo(config, unpaddedBytes16, 16)
          yield padded && unpadded
        },
      )
    val aes = aesKey.toList.map: config =>
      test("SecretKey: a wrong length names the expected and the actual length and nothing else") {
        rejects(config, marker, List("expected 32 bytes", "got 16"))
      }
    val keys = privateKey.toList.flatMap: config =>
      List(
        test("PrivateKey: base64 that is not a key leaks nothing") {
          rejects(config, longMarker, List("PKCS#8"))
        },
        test("PrivateKey: an illegal character does not leak through the decoder's message") {
          rejects(config, marker + "-", List("PKCS#8"), illegalCharacter)
        },
      )
    suite("secret decoders")((common ++ sixteen ++ aes ++ keys)*)

  private def load[A](config: Config[A], value: String): UIO[Either[Config.Error, A]] =
    TypesafeConfigProvider
      .fromHoconString(s"""$name = "$value"""")
      .kebabCase
      .load(config.nested(name))
      .either

  private def rejects[A](
      config: Config[A],
      value: String,
      mustMention: List[String],
      alsoForbidden: List[String] = Nil,
  ): UIO[TestResult] =
    load(config, value).map:
      case Right(_) => assertNever("decoded a value that must be rejected")
      case Left(error) =>
        val texts = rendered(error)
        val leaked = (pieces ++ alsoForbidden).exists(piece => texts.exists(_.contains(piece)))
        val mentionsAll = (name :: mustMention).forall(part => texts.exists(_.contains(part)))
        assertTrue(!leaked, mentionsAll)

  private def decodesTo[A <: Array[Byte]](config: Config[A], value: String, length: Int): UIO[TestResult] =
    load(config, value).map:
      case Right(decoded) => assertTrue(decoded.length == length)
      case Left(_) => assertNever("rejected a value that must be accepted")

  /** Everything a log line could print about the error: it, and each exception in its cause chain. */
  def rendered(error: Config.Error): List[String] =
    (error: Any) match
      case throwable: Throwable => causeChain(throwable).flatMap(t => t.toString :: Option(t.getMessage).toList)
      case other => List(other.toString)

  private def causeChain(throwable: Throwable): List[Throwable] =
    Option(throwable.getCause) match
      case Some(cause) => throwable :: causeChain(cause)
      case None => List(throwable)
