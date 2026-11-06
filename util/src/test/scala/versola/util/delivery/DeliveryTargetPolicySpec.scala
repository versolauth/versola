package versola.util.delivery

import io.opentelemetry.api
import versola.util.Email
import zio.*
import zio.http.{DnsResolver, Request, URL}
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*
import zio.test.TestAspect.{silentLogging, timeout, withLiveClock}

import java.net.{InetAddress, UnknownHostException}

object DeliveryTargetPolicySpec extends ZIOSpecDefault:

  private val closed = DeliveryTargetPolicy(allowPrivate = false)
  private val withPrivate = DeliveryTargetPolicy(allowPrivate = true)

  private def address(text: String): InetAddress = InetAddress.getByName(text)

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def resolverAnswering(answers: String*): DnsResolver = new DnsResolver:
    override def resolve(host: String)(using trace: Trace): ZIO[Any, UnknownHostException, Chunk[InetAddress]] =
      ZIO.succeed(Chunk.fromIterable(answers.map(address)))

  private val alwaysRefused =
    List("127.0.0.1", "127.8.9.10", "::1", "169.254.169.254", "169.254.0.1", "fe80::1", "0.0.0.0", "::", "224.0.0.1", "ff02::1")
  private val privateAddresses =
    List("10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "100.127.255.255", "fc00::1", "fd12:3456::1")
  private val publicAddresses = List("93.184.216.34", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111")

  def spec = suite("DeliveryTargetPolicy")(
    suite("refusal of an address")(
      test("loopback, link-local (the metadata address), unspecified and multicast are refused whatever the setting") {
        assertTrue(
          alwaysRefused.forall(text => closed.refusal(address(text)).isDefined),
          alwaysRefused.forall(text => withPrivate.refusal(address(text)).isDefined),
        )
      },
      test("a private address is refused unless the operator allows private targets") {
        assertTrue(
          privateAddresses.forall(text => closed.refusal(address(text)).contains("private")),
          privateAddresses.forall(text => withPrivate.refusal(address(text)).isEmpty),
        )
      },
      test("a public address is accepted") {
        assertTrue(publicAddresses.forall(text => closed.refusal(address(text)).isEmpty))
      },
      test("an IPv4-mapped IPv6 address is judged as the IPv4 address it carries") {
        assertTrue(
          closed.refusal(address("::ffff:127.0.0.1")).isDefined,
          closed.refusal(address("::ffff:169.254.169.254")).isDefined,
          closed.refusal(address("::ffff:10.0.0.1")).contains("private"),
          closed.refusal(address("::ffff:93.184.216.34")).isEmpty,
        )
      },
    ),
    suite("refusal of a host as written")(
      test("localhost and the names under it, however written") {
        assertTrue(List("localhost", "LOCALHOST", "localhost.", "api.localhost").forall(closed.refusalOfHost(_).isDefined))
      },
      test("the metadata names of the clouds") {
        assertTrue(List("metadata", "metadata.google.internal").forall(closed.refusalOfHost(_).isDefined))
      },
      test("an address literal in any form the JDK reads: dotted, integer, short, bracketed IPv6") {
        assertTrue(
          List("127.0.0.1", "2130706433", "127.1", "[::1]", "::1", "169.254.169.254", "0.0.0.0").forall(closed.refusalOfHost(_).isDefined),
        )
      },
      test("a private literal follows the setting") {
        assertTrue(
          closed.refusalOfHost("10.1.2.3").contains("private"),
          withPrivate.refusalOfHost("10.1.2.3").isEmpty,
          withPrivate.refusalOfHost("127.0.0.1").isDefined,
        )
      },
      test("a name is not judged without resolving it, and an address that is public is accepted") {
        assertTrue(
          closed.refusalOfHost("sms.example.com").isEmpty,
          closed.refusalOfHost("mail.example.com").isEmpty,
          closed.refusalOfHost("93.184.216.34").isEmpty,
        )
      },
    ),
    suite("GuardedDnsResolver")(
      test("hands on what a name resolves to when every address is allowed") {
        for answered <- GuardedDnsResolver(closed, resolverAnswering("93.184.216.34", "8.8.8.8")).resolve("sms.example.com")
        yield assertTrue(answered.size == 2)
      },
      test("refuses a name when any one of its addresses is not allowed, so the connection cannot take that one") {
        for
          refused <- GuardedDnsResolver(closed, resolverAnswering("93.184.216.34", "169.254.169.254")).resolve("sms.example.com").either
          message = refused.left.toOption.map(_.getMessage)
        yield assertTrue(
          refused.isLeft,
          message.exists(_.contains("link-local")),
          !message.exists(_.contains("169.254")),
        )
      },
      test("refuses a private answer unless private targets are allowed") {
        for
          closedResult <- GuardedDnsResolver(closed, resolverAnswering("10.0.0.7")).resolve("sms.internal").either
          openResult <- GuardedDnsResolver(withPrivate, resolverAnswering("10.0.0.7")).resolve("sms.internal").either
        yield assertTrue(closedResult.isLeft, openResult.isRight)
      },
    ),
    suite("the client of a saved provider")(
      test("does not connect to a host that resolves to loopback") {
        for
          client <- GuardedClient.make(closed)
          result <- ZIO.scoped(client.request(Request.get(URL.decode("http://localhost:9/").toOption.get))).either
        yield assertTrue(result.left.toOption.exists(_.getMessage.contains("delivery target refused")))
      }.provide(Scope.default, tracingLayer),
    ),
    suite("the SMTP sender of a saved server")(
      test("does not connect to a server on loopback, and nothing is sent") {
        for
          smtp <- ZIO.acquireRelease(ZIO.succeed(FakeSmtpServer.start()))(server => ZIO.succeed(server.close()))
          settings = SmtpSettings(
            host = "localhost",
            port = smtp.port,
            username = "mailer",
            password = Some("mail-secret"),
            from = Email("noreply@example.com"),
            subject = "Your code",
            startTls = false,
          )
          result <- EmailSender.smtp(settings, closed).send(Email("user@example.com"), "hello").either
        yield assertTrue(result.isLeft, smtp.messages.isEmpty)
      },
      test("the same server is reached by a sender that has no policy, as one configured in auth.conf is") {
        for
          smtp <- ZIO.acquireRelease(ZIO.succeed(FakeSmtpServer.start()))(server => ZIO.succeed(server.close()))
          settings = SmtpSettings(
            host = "localhost",
            port = smtp.port,
            username = "mailer",
            password = None,
            from = Email("noreply@example.com"),
            subject = "Your code",
            startTls = false,
          )
          _ <- EmailSender.smtp(settings).send(Email("user@example.com"), "hello")
        yield assertTrue(smtp.messages.size == 1)
      },
    ),
  ) @@ silentLogging @@ withLiveClock @@ timeout(60.seconds)
