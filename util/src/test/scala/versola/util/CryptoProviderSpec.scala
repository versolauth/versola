package versola.util

import versola.util.CryptoProvider.Mode
import zio.*
import zio.test.*

import java.security.Provider

object CryptoProviderSpec extends ZIOSpecDefault:

  /** Installed by the code under test but serving nothing, which is what a provider whose native
    * library loaded yet does not answer for our algorithms looks like. */
  private class Inert extends Provider("InertTest", "1", "serves nothing")

  private def tracked(): (Provider => Unit, String => Unit, () => List[String]) =
    val events = scala.collection.mutable.ListBuffer.empty[String]
    (provider => events += s"insert ${provider.getName}", name => events += s"remove $name", () => events.toList)

  def spec = suite("CryptoProvider")(
    suite("Mode.parse")(
      test("an unset or empty value uses the native provider when it works"):
        assertTrue(
          Mode.parse(None) == Right(Mode.Accp),
          Mode.parse(Some("  ")) == Right(Mode.Accp),
        )
      ,
      test("jdk is the explicit opt-out"):
        assertTrue(Mode.parse(Some("JDK")) == Right(Mode.Jdk))
      ,
      test("accp and accp-required are recognised whatever their case"):
        assertTrue(
          Mode.parse(Some("ACCP")) == Right(Mode.Accp),
          Mode.parse(Some(" accp-required ")) == Right(Mode.AccpRequired),
        )
      ,
      test("anything else is refused rather than read as the default"):
        assertTrue(Mode.parse(Some("accp_required")).isLeft),
    ),
    suite("install")(
      test("the JDK mode never asks for the provider"):
        val (insert, remove, events) = tracked()
        val outcome = CryptoProvider.install(Mode.Jdk, () => throw new AssertionError("loaded"), insert, remove)
        assertTrue(!outcome.native, events().isEmpty)
      ,
      test("accp falls back to the JDK and says why when the provider cannot be loaded"):
        val (insert, remove, events) = tracked()
        val outcome = CryptoProvider.install(Mode.Accp, () => Left("no native library"), insert, remove)
        assertTrue(!outcome.native, outcome.description.contains("no native library"), events().isEmpty)
      ,
      test("accp-required refuses to start when the provider cannot be loaded"):
        val (insert, remove, _) = tracked()
        val result = scala.util.Try(CryptoProvider.install(Mode.AccpRequired, () => Left("no native library"), insert, remove))
        assertTrue(
          result.failed.toOption.exists(_.isInstanceOf[IllegalStateException]),
          result.failed.toOption.exists(_.getMessage.contains("no native library")),
        )
      ,
      test("a provider that does not end up serving the algorithms is removed again, and required refuses it"):
        val (insert, remove, events) = tracked()
        val inert = Inert()
        val soft = CryptoProvider.install(Mode.Accp, () => Right(inert), insert, remove)
        val required = scala.util.Try(CryptoProvider.install(Mode.AccpRequired, () => Right(inert), insert, remove))
        assertTrue(
          !soft.native,
          required.isFailure,
          events() == List("insert InertTest", "remove InertTest", "insert InertTest", "remove InertTest"),
        ),
    ),
  )
