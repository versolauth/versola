package versola.util

import versola.util.TlsCipherPolicy.Mode
import zio.*
import zio.test.*

import java.io.{BufferedReader, File, InputStreamReader}
import java.lang.System.nanoTime
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.security.Security
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
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
      finally deleteTree(dir)
    }

  /** The TLS 1.2 suites RFC 9325 §4.2 recommends: the only ones a FAPI 2.0 server may offer. */
  private val Rfc9325Suites = Set(
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
  )

  /** A client that has not applied the policy, so it can offer a CBC suite. It tries every TLS 1.2 suite its JDK
    * supports against the listener one at a time, and prints the ones the listener agreed to. */
  private val Client =
    """import javax.net.ssl.*;
      |import java.security.cert.X509Certificate;
      |
      |public class Client {
      |  public static void main(String[] args) throws Exception {
      |    int port = Integer.parseInt(args[0]);
      |    SSLContext context = SSLContext.getInstance("TLS");
      |    context.init(null, new TrustManager[] { new X509TrustManager() {
      |      public void checkClientTrusted(X509Certificate[] chain, String type) {}
      |      public void checkServerTrusted(X509Certificate[] chain, String type) {}
      |      public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      |    } }, null);
      |    for (String suite : context.getSupportedSSLParameters().getCipherSuites()) {
      |      if (suite.contains("SCSV")) continue;
      |      try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("localhost", port)) {
      |        socket.setSoTimeout(5000);
      |        socket.setEnabledProtocols(new String[] { "TLSv1.2" });
      |        socket.setEnabledCipherSuites(new String[] { suite });
      |        socket.startHandshake();
      |        System.out.println("accepted " + suite);
      |      } catch (Exception refused) {
      |        // not agreed to, or not usable with the listener's certificate
      |      }
      |    }
      |  }
      |}
      |""".stripMargin

  /** The class path of the whole test run, which zio-http, Netty and the listener probe need. Under sbt that is the
    * URLs of its class loaders (the process's own class path holds only sbt); in a forked test JVM, the class path. */
  private def fullClasspath: List[String] =
    val loaders = Iterator.iterate(Thread.currentThread.getContextClassLoader)(_.getParent).takeWhile(_ != null).toList
    val urls = loaders.collect { case loader: URLClassLoader =>
      loader.getURLs.toList.filter(_.getProtocol == "file").map(url => Paths.get(url.toURI).toString)
    }.flatten
    (urls ++ sys.props("java.class.path").split(File.pathSeparator).toList).filter(_.nonEmpty).distinct

  private def deleteTree(dir: Path): Unit =
    Using.resource(Files.walk(dir))(_.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists))

  /** Starts the listener probe in a JVM of its own (mode `plain`, or `policy` to apply the policy first), waits for it
    * to say which port it listens on, runs `use` with that port, and stops it. */
  private def withListener[A](mode: String, algorithm: String, dir: Path)(use: Int => A): A =
    val certificate = TestCertificates.generate(subject = "CN=localhost", algorithm = algorithm)
    val certPath = Files.writeString(dir.resolve("server.crt"), certificate.certificatePem)
    val keyPath = Files.writeString(dir.resolve("server.key"), certificate.privateKeyPem)
    // the class path is long: the launcher reads it from a file, with forward slashes (a backslash would be an escape)
    val classpath = fullClasspath.map(_.replace('\\', '/')).mkString(File.pathSeparator)
    val argfile = Files.writeString(dir.resolve("listener.args"), s"-cp \"$classpath\"")
    val builder =
      ProcessBuilder(javaBinary, s"@$argfile", "versola.util.TlsListenerProbe", mode, certPath.toString, keyPath.toString).redirectErrorStream(true)
    builder.environment().remove("VERSOLA_TLS_CIPHER_POLICY")
    val process = builder.start()
    val lines = LinkedBlockingQueue[String]()
    val reader = Thread(() =>
      Using.resource(BufferedReader(InputStreamReader(process.getInputStream, UTF_8)))(in =>
        Iterator.continually(in.readLine()).takeWhile(_ != null).foreach(line => lines.put(line)),
      ),
    )
    reader.setDaemon(true)
    reader.start()
    try
      val deadline = nanoTime + TimeUnit.SECONDS.toNanos(90)
      val output = ListBuffer.empty[String]
      val port = Iterator
        .continually(lines.poll(1, TimeUnit.SECONDS))
        .takeWhile(_ => nanoTime < deadline && (process.isAlive || !lines.isEmpty))
        .flatMap(Option(_))
        .tapEach(output += _)
        .collectFirst { case line if line.startsWith("listening ") => line.stripPrefix("listening ").trim.toInt }
      use(port.getOrElse(throw new AssertionError(s"the listener did not come up:\n${output.mkString("\n")}")))
    finally
      process.destroyForcibly()
      // Until it has gone it may hold files open, and on Windows deleting the directory would fail first.
      process.waitFor(10, TimeUnit.SECONDS)

  /** The TLS 1.2 suites a zio-http listener agrees to, as seen by a client that has not applied the policy. */
  private def servedByListener(mode: String, algorithm: String): Task[Set[String]] =
    ZIO.attemptBlocking {
      val dir = Files.createTempDirectory("tls-listener")
      try
        val source = Files.writeString(dir.resolve("Client.java"), Client)
        withListener(mode, algorithm, dir): port =>
          run(List(javaBinary, source.toString, port.toString), dir)
            .collect { case line if line.startsWith("accepted ") => line.stripPrefix("accepted ").trim }
            .toSet
      finally deleteTree(dir)
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
    ) @@ TestAspect.sequential,
    suite("a zio-http listener, probed by a client that has not applied the policy")(
      test("without the policy it agrees to CBC suites, so the next test removes something"):
        // A JDK that already refuses both ciphers has none to show, and the client could not offer them either.
        val jdkRefuses = Option(Security.getProperty("jdk.tls.disabledAlgorithms")).exists(value =>
          List("AES_128_CBC", "AES_256_CBC").forall(value.contains),
        )
        if jdkRefuses then ZIO.succeed(assertCompletes)
        else servedByListener("plain", "RSA").map(accepted => assertTrue(accepted.exists(_.contains("_CBC_"))))
      ,
      test("with the policy it agrees to GCM suites only, for an RSA certificate and for an EC one"):
        ZIO
          .foreach(List("RSA", "EC"))(algorithm => servedByListener("policy", algorithm).map(algorithm -> _))
          .map(served =>
            assertTrue(
              served.forall((_, accepted) => accepted.exists(_.contains("_GCM_"))),
              served.forall((_, accepted) => !accepted.exists(_.contains("_CBC_"))),
              // nothing RFC 9325 4.2 does not recommend: a Netty upgrade that adds ChaCha20 or DHE to its defaults fails here, and so
              // does a JDK that does not disable TLS_RSA_* (Netty's list holds TLS_RSA_WITH_AES_128_GCM_SHA256; the JDK's default drops it)
              served.forall((_, accepted) => accepted.subsetOf(Rfc9325Suites)),
            ),
          ),
    ) @@ TestAspect.sequential,
  )
