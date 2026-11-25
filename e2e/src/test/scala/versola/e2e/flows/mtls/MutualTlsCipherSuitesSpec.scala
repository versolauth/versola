package versola.e2e.flows.mtls

import versola.e2e.support.E2EConfig
import zio.*
import zio.http.{Client, Request, URL}
import zio.test.*

import java.io.FileInputStream
import java.net.{InetSocketAddress, Socket, URI}
import java.security.KeyStore
import java.security.cert.{CertificateFactory, X509Certificate}
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.{SSLContext, SSLEngine, SSLHandshakeException, SSLSocket, TrustManager, TrustManagerFactory, X509ExtendedTrustManager}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** FAPI 2.0 Security Profile §5.2.2 over RFC 9325 §4.2, at the level that sees the staged processes: the three mutual-TLS
  * listeners (`auth`, `central`, `edge`) agree to TLS 1.2 only with the four AES-GCM suites, and no AES-CBC suite.
  *
  * [[versola.util.TlsCipherPolicySpec]] holds the rule against a zio-http listener started by the spec. What only this level
  * shows is that the services as staged -- `VersolaApp.bootstrap`, the real `SSLConfig` of each listener, the JDK of the
  * image -- end up with that listener, and that each of them says so (`tls_cbc_refused 1` on `/metrics`).
  *
  * The client here is a plain `SSLSocket`, and this JVM does not run `VersolaApp`, so the policy is not applied to it and it
  * can offer a CBC suite. That is what gives a refusal its meaning: a client under the policy could not offer one at all.
  * A refusal is the alert `handshake_failure` the server sends when it shares no suite with the client; a suite the client
  * itself cannot offer is told apart and never counts as one.
  */
object MutualTlsCipherSuitesSpec extends ZIOSpec[E2EConfig & Client]:

  override val bootstrap: ZLayer[Any, Any, E2EConfig & Client] = E2EConfig.live ++ Client.default

  /** RFC 9325 §4.2: the TLS 1.2 suites it recommends. */
  private val recommended: Set[String] = Set(
    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
  )

  /** The listeners' certificates are RSA (`scripts/gen-env.scala`), so these are the two suites of the four they can use. */
  private val cbcSuite = "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"
  private val gcmSuite = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"

  /** What one handshake attempt with one suite came to. */
  private enum Probe:
    /** The server answered `handshake_failure`: it shares no suite with the client. */
    case Refused

    /** This JVM would not offer the suite (disabled in its own security properties): says nothing about the server. */
    case NotOffered

    /** The server took the suite, or failed later for another reason (the missing client certificate, as an alert that is not
      * `handshake_failure`).
      */
    case Agreed

  private enum Listener(val label: String):
    case Auth extends Listener("auth")
    case Central extends Listener("central")
    case Edge extends Listener("edge")

  /** Where a listener is, which certificates the client trusts to get as far as the suite negotiation, and where its
    * diagnostics port serves `/metrics`.
    */
  private final case class Target(host: String, port: Int, trusted: String, diagnosticsUrl: String)

  private def target(config: E2EConfig, listener: Listener): Task[Target] =
    val (url, trusted, diagnostics) = listener match
      case Listener.Auth => (config.authMutualTlsUrl, config.authMutualTlsTrustedCertificates, config.authDiagnosticsUrl)
      case Listener.Central => (config.centralMutualTlsUrl, config.centralMutualTlsServerCertificate, config.centralDiagnosticsUrl)
      case Listener.Edge => (config.edgeMutualTlsUrl, config.edgeMutualTlsTrustedCertificates, config.edgeDiagnosticsUrl)
    ZIO.attempt:
      val uri = URI.create(url)
      Target(host = uri.getHost, port = uri.getPort, trusted = trusted, diagnosticsUrl = diagnostics)

  /** Trusts what `trusted` holds, and notes that the server's certificate reached it. The certificate follows the server's
    * choice of suite, so seeing it means the suite was agreed, whichever alert the server then sends for the missing client
    * certificate (OpenSSL and the JDK differ there).
    */
  private final class NotingTrustManager(delegate: X509ExtendedTrustManager, certificateSeen: AtomicBoolean) extends X509ExtendedTrustManager:
    def checkServerTrusted(chain: Array[X509Certificate], authType: String, socket: Socket): Unit =
      certificateSeen.set(true)
      delegate.checkServerTrusted(chain, authType, socket)
    def checkServerTrusted(chain: Array[X509Certificate], authType: String, engine: SSLEngine): Unit =
      certificateSeen.set(true)
      delegate.checkServerTrusted(chain, authType, engine)
    def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
      certificateSeen.set(true)
      delegate.checkServerTrusted(chain, authType)
    def checkClientTrusted(chain: Array[X509Certificate], authType: String, socket: Socket): Unit = delegate.checkClientTrusted(chain, authType, socket)
    def checkClientTrusted(chain: Array[X509Certificate], authType: String, engine: SSLEngine): Unit = delegate.checkClientTrusted(chain, authType, engine)
    def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = delegate.checkClientTrusted(chain, authType)
    def getAcceptedIssuers: Array[X509Certificate] = delegate.getAcceptedIssuers

  private def sslContext(trusted: String, certificateSeen: AtomicBoolean): Task[SSLContext] =
    ZIO.attemptBlocking:
      val certificates = Using.resource(FileInputStream(trusted))(in =>
        CertificateFactory.getInstance("X.509").generateCertificates(in).asScala.toList,
      )
      val store = KeyStore.getInstance(KeyStore.getDefaultType)
      store.load(null, null)
      certificates.zipWithIndex.foreach: (certificate, index) =>
        store.setCertificateEntry(s"trusted-$index", certificate)
      val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      factory.init(store)
      val managers = factory.getTrustManagers.collect { case manager: X509ExtendedTrustManager => NotingTrustManager(manager, certificateSeen) }
      val context = SSLContext.getInstance("TLSv1.2")
      context.init(null, managers.toArray[TrustManager], null)
      context

  /** The TLS 1.2 suites this JVM supports: everything but the TLS 1.3 ones and the signalling pseudo-suites. */
  private def clientSuites(trusted: String): Task[List[String]] =
    sslContext(trusted, AtomicBoolean(false)).map: context =>
      context.getSocketFactory.getSupportedCipherSuites.toList
        .filter(_.startsWith("TLS_"))
        .filterNot(suite => suite.startsWith("TLS_AES_") || suite.startsWith("TLS_CHACHA20_") || suite.contains("SCSV"))
        .sorted

  /** One TLS 1.2 handshake offering exactly `suite`. Anything that is neither the server's certificate arriving nor a
    * `handshake_failure` alert nor this JVM declining to offer the suite (the port is closed, a timeout) fails the test
    * instead of being read as a refusal.
    */
  private def probe(target: Target, suite: String): Task[Probe] =
    val certificateSeen = AtomicBoolean(false)
    ZIO.scoped:
      for
        context <- sslContext(target.trusted, certificateSeen)
        socket <- ZIO.fromAutoCloseable(ZIO.attemptBlocking(context.getSocketFactory.createSocket()))
        result <- ZIO.attemptBlocking:
          socket match
            case tls: SSLSocket =>
              tls.setEnabledProtocols(Array("TLSv1.2"))
              tls.setEnabledCipherSuites(Array(suite))
              tls.setSoTimeout(10000)
              tls.connect(InetSocketAddress(target.host, target.port), 10000)
              tls.startHandshake()
            case other => throw IllegalStateException(s"expected an SSLSocket, got ${other.getClass.getName}")
        .either
        probe <- result match
          case _ if certificateSeen.get => ZIO.succeed(Probe.Agreed)
          case Right(_) => ZIO.succeed(Probe.Agreed)
          case Left(error: SSLHandshakeException) if Option(error.getMessage).exists(_.contains("Received fatal alert: handshake_failure")) =>
            ZIO.succeed(Probe.Refused)
          case Left(error: SSLHandshakeException) if Option(error.getMessage).exists(_.contains("No appropriate protocol")) =>
            ZIO.succeed(Probe.NotOffered)
          case Left(error) => ZIO.fail(error)
      yield probe

  private def metric(target: Target, client: Client): Task[String] =
    ZIO.scoped:
      for
        url <- ZIO.fromEither(URL.decode(s"${target.diagnosticsUrl}/metrics")).mapError(error => RuntimeException(error))
        response <- client.batched(Request.get(url))
        body <- response.body.asString
      yield body

  private val refusedGauge = """(?m)^tls_cbc_refused(\{[^}]*\})?\s+1(\.0+)?\s*$""".r

  def spec = suite("Mutual TLS listeners agree to no AES-CBC suite (RFC 9325 §4.2, FAPI 2.0 §5.2.2)")(
    Listener.values.toList.map(listener =>
      suite(listener.label)(
        test(s"a client offering only $cbcSuite is refused with handshake_failure") {
          for
            config <- ZIO.service[E2EConfig]
            target <- target(config, listener)
            outcome <- probe(target, cbcSuite)
          yield assertTrue(outcome == Probe.Refused)
            .label(s"$outcome: Refused is the only answer that shows the listener turned CBC down ($cbcSuite NotOffered means this JVM cannot offer it)")
        },
        test(s"a client offering only $gcmSuite gets past the suite negotiation") {
          for
            config <- ZIO.service[E2EConfig]
            target <- target(config, listener)
            outcome <- probe(target, gcmSuite)
          yield assertTrue(outcome == Probe.Agreed)
            .label(s"expected the GCM suite to be taken (it then fails on the missing client certificate, with an alert that is not handshake_failure), got $outcome")
        },
        test("every TLS 1.2 suite this JVM supports is refused or one of the four of RFC 9325 §4.2") {
          for
            config <- ZIO.service[E2EConfig]
            target <- target(config, listener)
            suites <- clientSuites(target.trusted)
            outcomes <- ZIO.foreach(suites)(suite => probe(target, suite).map(suite -> _))
            agreed = outcomes.collect { case (suite, Probe.Agreed) => suite }
            offered = outcomes.count(_._2 != Probe.NotOffered)
          yield assertTrue(agreed.toSet.subsetOf(recommended), agreed.nonEmpty, offered > 1)
            .label(s"suites the listener agreed to: ${agreed.mkString(", ")}; the ones outside RFC 9325 §4.2: ${agreed.filterNot(recommended).mkString(", ")}")
        },
        test("its /metrics says the policy is in force: tls_cbc_refused 1") {
          for
            config <- ZIO.service[E2EConfig]
            target <- target(config, listener)
            client <- ZIO.service[Client]
            body <- metric(target, client)
          yield assertTrue(refusedGauge.findFirstIn(body).isDefined)
            .label(s"expected a line 'tls_cbc_refused 1' on ${target.diagnosticsUrl}/metrics; the policy is off (VERSOLA_TLS_CIPHER_POLICY=jdk) or the gauge is missing")
        },
      ),
    )*,
  )
