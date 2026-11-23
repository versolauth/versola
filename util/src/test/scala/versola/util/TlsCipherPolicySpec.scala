package versola.util

import versola.util.TlsCipherPolicy.Mode
import zio.*
import zio.test.*

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.reflect.Enum as ScalaEnum
import scala.util.{Try, Using}

object TlsCipherPolicySpec extends ZIOSpecDefault:

  /** What a process that has just started does, in the three ways that matter, printing what it finds:
    *  - `plain`: the CBC suites a TLS context supports with nothing applied;
    *  - `policy`: the real `TlsCipherPolicy.installFromEnv()`, then the CBC suites a TLS context supports;
    *  - `latched`: a TLS context is built first, as a library or an agent that started earlier would, then the
    *    same `installFromEnv()`, which has to refuse the start because the JDK has already read the property.
    * They run in processes of their own because the JDK reads `jdk.tls.disabledAlgorithms` once, and the process
    * running the specs has used TLS already. The policy is applied the way the services apply it, with
    * `Security.setProperty` at run time, not through `-Djava.security.properties`, which cannot be too late. */
  private val Probe =
    """import javax.net.ssl.SSLContext;
      |
      |public class Probe {
      |  public static void main(String[] args) throws Exception {
      |    String mode = args[0];
      |    if (mode.equals("latched")) SSLContext.getInstance("TLS");
      |    if (!mode.equals("plain")) {
      |      try {
      |        System.out.println("outcome " + versola.util.TlsCipherPolicy.installFromEnv());
      |      } catch (IllegalStateException e) {
      |        System.out.println("failed " + e.getMessage());
      |        return;
      |      }
      |    }
      |    SSLContext context = SSLContext.getInstance("TLS");
      |    context.init(null, null, null);
      |    for (String suite : context.getSupportedSSLParameters().getCipherSuites())
      |      if (suite.contains("_CBC_")) System.out.println("cbc " + suite);
      |  }
      |}
      |""".stripMargin

  private def javaBinary: String =
    val bin = Paths.get(sys.props("java.home"), "bin")
    List("java", "java.exe").map(bin.resolve).find(Files.isRegularFile(_)) match
      case Some(tool) => tool.toString
      case None => throw new AssertionError(s"java is not in $bin")

  private def location(clazz: Class[?]): String =
    Paths.get(clazz.getProtectionDomain.getCodeSource.getLocation.toURI).toString

  /** What the probe needs: the classes of `util`, and the Scala libraries they are built on. */
  private def classpath: String =
    List(classOf[TlsCipherPolicy.type], classOf[Option[?]], classOf[ScalaEnum])
      .map(location).distinct.mkString(File.pathSeparator)

  /** Runs the command, giving up after two minutes, and returns what it printed. A command that fails is the
    * spec's problem, not a silent empty answer: its output is part of the failure. */
  private def run(command: List[String], dir: Path): List[String] =
    val log = Files.createTempFile(dir, "run", ".log")
    val builder = ProcessBuilder(command.asJava).redirectErrorStream(true).redirectOutput(log.toFile)
    builder.environment().remove("VERSOLA_TLS_CIPHER_POLICY")
    val process = builder.start()
    if !process.waitFor(120, TimeUnit.SECONDS) then
      process.destroyForcibly()
      // Until it has gone it may still hold its log open, and on Windows deleting the directory would fail first.
      process.waitFor(10, TimeUnit.SECONDS)
      throw new AssertionError(s"timed out: ${command.mkString(" ")}")
    val output = String(Files.readAllBytes(log), UTF_8).linesIterator.toList
    if process.exitValue != 0 then
      throw new AssertionError(s"exit ${process.exitValue}: ${command.mkString(" ")}\n${output.mkString("\n")}")
    output

  /** The lines the probe printed in the given mode. */
  private def probe(mode: String): Task[List[String]] =
    ZIO.attemptBlocking {
      val dir = Files.createTempDirectory("tls-cipher-policy")
      try
        val source = Files.writeString(dir.resolve("Probe.java"), Probe)
        run(List(javaBinary, "-cp", classpath, source.toString, mode), dir)
          .filter(line => List("cbc ", "outcome ", "failed ").exists(line.startsWith))
      finally
        Using.resource(Files.walk(dir))(_.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists))
    }

  def spec = suite("TlsCipherPolicy")(
    suite("Mode.parse")(
      test("an unset or empty value refuses the CBC suites"):
        assertTrue(Mode.parse(None) == Right(Mode.Bcp195), Mode.parse(Some("  ")) == Right(Mode.Bcp195))
      ,
      test("jdk is the explicit opt-out, whatever its case"):
        assertTrue(Mode.parse(Some("JDK")) == Right(Mode.Jdk), Mode.parse(Some(" bcp195 ")) == Right(Mode.Bcp195))
      ,
      test("anything else is refused rather than read as the default"):
        assertTrue(Mode.parse(Some("gcm-only")).isLeft),
    ),
    suite("withRefused")(
      test("adds the two AES-CBC ciphers after what the JDK already disables"):
        assertTrue(
          TlsCipherPolicy.withRefused(Some("SSLv3, RC4, DES")) == "SSLv3, RC4, DES, AES_128_CBC, AES_256_CBC",
        )
      ,
      test("does not add a cipher that is already there, so applying it twice changes nothing"):
        val once = TlsCipherPolicy.withRefused(Some("SSLv3, RC4"))
        assertTrue(
          TlsCipherPolicy.withRefused(Some(once)) == once,
          TlsCipherPolicy.withRefused(Some("AES_128_CBC")).split(", ").toList == List("AES_128_CBC", "AES_256_CBC"),
        )
      ,
      test("starts from nothing when the JDK has no value or an empty one"):
        assertTrue(
          TlsCipherPolicy.withRefused(None) == "AES_128_CBC, AES_256_CBC",
          TlsCipherPolicy.withRefused(Some("  ")) == "AES_128_CBC, AES_256_CBC",
        ),
    ),
    suite("install")(
      test("the default writes the property with the CBC ciphers added and reports it refused"):
        val written = ListBuffer.empty[String]
        val outcome = TlsCipherPolicy.install(Mode.Bcp195, Some("SSLv3"), written += _, () => Nil)
        assertTrue(
          written.toList == List("SSLv3, AES_128_CBC, AES_256_CBC"),
          outcome.refused,
          outcome.description.contains("AES_128_CBC"),
        )
      ,
      test("refuses the start when the JDK still offers a CBC suite after the property is written"):
        val written = ListBuffer.empty[String]
        val offered = List("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA")
        val result = Try(TlsCipherPolicy.install(Mode.Bcp195, None, written += _, () => offered)).toEither
        assertTrue(
          result.left.exists(_.isInstanceOf[IllegalStateException]),
          result.left.exists(_.getMessage.contains("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA")),
          result.left.exists(_.getMessage.contains("VERSOLA_TLS_CIPHER_POLICY=jdk")),
        )
      ,
      test("the jdk mode leaves the property alone and does not say it is refused"):
        val written = ListBuffer.empty[String]
        val outcome = TlsCipherPolicy.install(Mode.Jdk, Some("SSLv3"), written += _, () => throw new AssertionError("asked"))
        assertTrue(written.isEmpty, !outcome.refused),
    ),
    suite("in a process that has just started")(
      test("with nothing applied the JDK supports CBC suites, so the next test removes something"):
        // A JDK that already refuses both ciphers has none to show; the next test still holds it to none.
        probe("plain").map(lines => assertTrue(lines.exists(_.startsWith("cbc ")) || lines.isEmpty))
      ,
      test("the policy, applied with Security.setProperty at start, leaves the JDK no CBC suite"):
        probe("policy").map(lines =>
          assertTrue(lines.exists(_.startsWith("outcome Outcome(true,")), !lines.exists(_.startsWith("cbc "))),
        )
      ,
      test("the policy applied after a TLS context exists refuses the start instead of claiming success"):
        probe("latched").map(lines =>
          assertTrue(lines.exists(line => line.startsWith("failed ") && line.contains("still offers CBC"))),
        ),
    ),
  )
