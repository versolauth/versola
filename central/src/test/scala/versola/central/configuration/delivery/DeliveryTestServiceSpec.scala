package versola.central.configuration.delivery

import org.scalamock.stubs.ZIOStubs
import jakarta.mail.{AuthenticationFailedException, SendFailedException}
import versola.central.configuration.delivery.DeliveryTestService.{Outcome, Refused, Request as TestRequest}
import versola.util.Secret
import versola.util.delivery.FakeSmtpServer
import zio.*
import zio.http.{Request as HttpRequest, *}
import zio.json.*
import zio.test.*
import zio.test.TestAspect.{silentLogging, withLiveClock}

import java.net.{ConnectException, SocketTimeoutException, UnknownHostException}
import java.time.Instant

/** Sends through real senders: a small SMTP server in the test, and a recorded HTTP route for the SMS provider. What is
  * checked is what reaches them, and what the administrator is told. */
object DeliveryTestServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private def smtpBlock(port: Int, host: String = "localhost", username: String = "mailer") =
    SmtpDeliverySettings(
      host = host,
      port = port,
      username = username,
      from = "noreply@example.com",
      subject = "Delivery check",
      startTls = false,
      enabled = true,
    )

  private val smsBlock = OtpProviderDeliverySettings(
    method = "POST",
    url = "https://sms.example.com/send",
    username = Some("sms"),
    body = Map("phones" -> "{{phone}}", "mes" -> "{{message}}"),
    enabled = true,
  )

  private def saved(
      smtp: Option[SmtpDeliverySettings] = None,
      smtpPassword: Option[String] = None,
      otpProvider: Option[OtpProviderDeliverySettings] = None,
      otpProviderPassword: Option[String] = None,
  ) =
    DeliverySettingsRecord(
      smtp = smtp,
      smtpPassword = smtpPassword.map(Secret.fromString),
      otpProvider = otpProvider,
      otpProviderPassword = otpProviderPassword.map(Secret.fromString),
    )

  private def emailRequest(block: SmtpDeliverySettings, password: Option[String] = None, to: String = "admin@example.com") =
    TestRequest(channel = "email", to = to, smtp = Some(block), otpProvider = None, password = password)

  private def smsRequest(block: OtpProviderDeliverySettings, password: Option[String] = None, to: String = "+12025551234") =
    TestRequest(channel = "sms", to = to, smtp = None, otpProvider = Some(block), password = password)

  private class Env(record: Option[DeliverySettingsRecord] = None):
    val settings = stub[DeliverySettingsService]
    def service(client: Client, attempted: Chunk[Instant] = Chunk.empty): UIO[DeliveryTestService] =
      for
        _ <- settings.getSettings.succeedsWith(record)
        attempts <- Ref.make(attempted)
      yield DeliveryTestService.Impl(settingsService = settings, client = client, attempts = attempts)

  private def server: ZIO[Scope, Nothing, FakeSmtpServer] =
    ZIO.acquireRelease(ZIO.succeed(FakeSmtpServer.start()))(smtp => ZIO.succeed(smtp.close()))

  private def provider(status: Status = Status.Ok, body: String = ""): ZIO[TestClient, Nothing, Ref[Option[(HttpRequest, String)]]] =
    for
      seen <- Ref.make(Option.empty[(HttpRequest, String)])
      _ <- TestClient.addRoutes(
        Handler.fromFunctionZIO[HttpRequest](request =>
          request.body.asString.orDie.flatMap(text => seen.set(Some(request -> text))).as(Response.text(body).status(status)),
        ).toRoutes,
      )
    yield seen

  def spec = suite("DeliveryTestService")(
    suite("email")(
      test("sends one message to the address, from the block under test, with the typed password") {
        val env = Env()
        for
          smtp <- server
          client <- ZIO.service[Client]
          service <- env.service(client)
          result <- service.send(emailRequest(smtpBlock(smtp.port), password = Some("typed-secret")))
          message <- ZIO.fromOption(smtp.messages.headOption).orElseFail(RuntimeException("the server received nothing"))
        yield assertTrue(
          result == Right(Outcome.Delivered),
          message.from == "noreply@example.com",
          message.recipients == List("admin@example.com"),
          message.header("Subject").contains("Delivery check"),
          message.body.contains(DeliveryTestService.TestMessage),
          message.user.contains("mailer"),
          message.password.contains("typed-secret"),
        )
      },
      test("uses the saved password when none is typed, for the server it was saved for") {
        for
          smtp <- server
          block = smtpBlock(smtp.port)
          env = Env(Some(saved(smtp = Some(block), smtpPassword = Some("saved-secret"))))
          client <- ZIO.service[Client]
          service <- env.service(client)
          result <- service.send(emailRequest(block.copy(subject = "Edited, not saved yet")))
          message <- ZIO.fromOption(smtp.messages.headOption).orElseFail(RuntimeException("the server received nothing"))
        yield assertTrue(
          result == Right(Outcome.Delivered),
          message.password.contains("saved-secret"),
          message.header("Subject").contains("Edited, not saved yet"),
        )
      },
      test("refuses to send the saved password to another server, port or account, and sends nothing") {
        for
          smtp <- server
          block = smtpBlock(smtp.port)
          env = Env(Some(saved(smtp = Some(block), smtpPassword = Some("saved-secret"))))
          client <- ZIO.service[Client]
          service <- env.service(client)
          results <- ZIO.foreach(List(block.copy(host = "127.0.0.1"), block.copy(port = smtp.port + 1), block.copy(username = "someone-else")))(other =>
            service.send(emailRequest(other)),
          )
        yield assertTrue(
          results == List.fill(3)(Left(Refused("password must be entered again when the server address, port or username changes"))),
          smtp.messages.isEmpty,
        )
      },
      test("sends without authenticating when no password is typed and none is saved") {
        val env = Env(None)
        for
          smtp <- server
          client <- ZIO.service[Client]
          service <- env.service(client)
          result <- service.send(emailRequest(smtpBlock(smtp.port)))
          message <- ZIO.fromOption(smtp.messages.headOption).orElseFail(RuntimeException("the server received nothing"))
        yield assertTrue(result == Right(Outcome.Delivered), message.user.isEmpty)
      },
      test("says the server could not be reached, without repeating anything the system said") {
        val env = Env(None)
        for
          smtp <- server
          port = smtp.port
          _ <- ZIO.succeed(smtp.close())
          client <- ZIO.service[Client]
          service <- env.service(client)
          result <- service.send(emailRequest(smtpBlock(port)))
        yield assertTrue(result == Right(Outcome.Failed(code = "connection_failed", message = "The server could not be reached at that address and port.")))
      },
      test("refuses what cannot be tried, naming the field, and sends nothing") {
        val env = Env(None)
        for
          smtp <- server
          client <- ZIO.service[Client]
          service <- env.service(client)
          block = smtpBlock(smtp.port)
          results <- ZIO.foreach(List(
            emailRequest(block.copy(from = "not-an-address")),
            emailRequest(block.copy(port = 0)),
            emailRequest(block, to = "not-an-address"),
            emailRequest(block, to = "admin@example.com\r\nBcc: x@example.com"),
            TestRequest(channel = "email", to = "admin@example.com", smtp = None, otpProvider = None, password = None),
            TestRequest(channel = "fax", to = "admin@example.com", smtp = Some(block), otpProvider = None, password = None),
          ))(service.send)
        yield assertTrue(
          results.map(_.left.map(_.message.takeWhile(_ != ' '))) == List(
            Left("smtp.from"),
            Left("smtp.port"),
            Left("to"),
            Left("to"),
            Left("smtp"),
            Left("channel"),
          ),
          smtp.messages.isEmpty,
        )
      },
    ),
    suite("sms")(
      test("sends one request through the block under test, with the typed password as HTTP Basic") {
        val env = Env()
        for
          seen <- provider()
          client <- ZIO.service[Client]
          service <- env.service(client)
          result <- service.send(smsRequest(smsBlock, password = Some("typed-secret")))
          (request, body) <- seen.get.someOrFail(RuntimeException("no request captured"))
          fields <- ZIO.fromEither(body.fromJson[Map[String, String]]).mapError(RuntimeException(_))
        yield assertTrue(
          result == Right(Outcome.Delivered),
          request.method == Method.POST,
          request.header(Header.Authorization).contains(Header.Authorization.Basic("sms", "typed-secret")),
          fields == Map("phones" -> "+12025551234", "mes" -> DeliveryTestService.TestMessage),
        )
      },
      test("uses the saved password when none is typed, for the endpoint it was saved for") {
        val env = Env(Some(saved(otpProvider = Some(smsBlock), otpProviderPassword = Some("saved-secret"))))
        for
          seen <- provider()
          client <- ZIO.service[Client]
          service <- env.service(client)
          result <- service.send(smsRequest(smsBlock.copy(url = "https://sms.example.com/v2/send")))
          (request, _) <- seen.get.someOrFail(RuntimeException("no request captured"))
        yield assertTrue(result == Right(Outcome.Delivered), request.header(Header.Authorization).contains(Header.Authorization.Basic("sms", "saved-secret")))
      },
      test("refuses to send the saved password to another host or account, and sends nothing") {
        val env = Env(Some(saved(otpProvider = Some(smsBlock), otpProviderPassword = Some("saved-secret"))))
        for
          seen <- provider()
          client <- ZIO.service[Client]
          service <- env.service(client)
          results <- ZIO.foreach(List(smsBlock.copy(url = "https://sms.attacker.example/send"), smsBlock.copy(username = Some("someone-else"))))(other =>
            service.send(smsRequest(other)),
          )
          captured <- seen.get
        yield assertTrue(
          results.forall(_.left.exists(_.message.startsWith("password must be entered again"))),
          captured.isEmpty,
        )
      },
      test("says which status the provider answered with, and does not repeat what it said") {
        val env = Env(None)
        for
          _ <- provider(Status.ServiceUnavailable, body = "internal host db-7 is down, token=abc123")
          client <- ZIO.service[Client]
          service <- env.service(client)
          result <- service.send(smsRequest(smsBlock))
        yield assertTrue(
          result == Right(Outcome.Failed(code = "provider_status", message = "The provider answered with status 503.")),
          !result.toString.contains("db-7"),
        )
      },
      test("refuses what cannot be tried, naming the field, and sends nothing") {
        val env = Env(None)
        for
          seen <- provider()
          client <- ZIO.service[Client]
          service <- env.service(client)
          results <- ZIO.foreach(List(
            smsRequest(smsBlock.copy(url = "https://u:p@sms.example.com/send")),
            smsRequest(smsBlock.copy(method = "DELETE")),
            smsRequest(smsBlock, to = "not-a-number"),
            TestRequest(channel = "sms", to = "+12025551234", smtp = None, otpProvider = None, password = None),
          ))(service.send)
          captured <- seen.get
        yield assertTrue(
          results.map(_.left.map(_.message.takeWhile(_ != ' '))) == List(Left("otpProvider.url"), Left("otpProvider.method"), Left("to"), Left("otpProvider")),
          captured.isEmpty,
        )
      },
    ),
    suite("the limit")(
      test("refuses the sixth message within a minute without sending it, and answers a rate limit, not a bad request") {
        val env = Env()
        for
          smtp <- server
          client <- ZIO.service[Client]
          service <- env.service(client)
          sent <- ZIO.foreach(1 to DeliveryTestService.Limit)(_ => service.send(emailRequest(smtpBlock(smtp.port))))
          refused <- service.send(emailRequest(smtpBlock(smtp.port)))
        yield assertTrue(
          sent.forall(_ == Right(Outcome.Delivered)),
          refused == Left(Refused(message = "too many test messages, try again in a minute", rateLimited = true)),
          smtp.messages.length == DeliveryTestService.Limit,
        )
      },
      test("counts only the messages of the last minute") {
        val env = Env()
        for
          smtp <- server
          client <- ZIO.service[Client]
          now <- Clock.instant
          old = Chunk.fill(DeliveryTestService.Limit)(now.minusSeconds(120))
          service <- env.service(client, attempted = old)
          result <- service.send(emailRequest(smtpBlock(smtp.port)))
        yield assertTrue(result == Right(Outcome.Delivered))
      },
      test("counts a request that is refused for what it asks as no message at all") {
        val env = Env()
        for
          smtp <- server
          client <- ZIO.service[Client]
          service <- env.service(client)
          _ <- ZIO.foreach(1 to 10)(_ => service.send(emailRequest(smtpBlock(smtp.port), to = "not-an-address")))
          result <- service.send(emailRequest(smtpBlock(smtp.port)))
        yield assertTrue(result == Right(Outcome.Delivered))
      },
    ),
    suite("the request")(
      test("does not print its password") {
        val printed = emailRequest(smtpBlock(25), password = Some("typed-s3cret")).toString
        assertTrue(!printed.contains("typed-s3cret"))
      },
    ),
    suite("failed")(
      test("names what to look at, from a closed set") {
        assertTrue(
          DeliveryTestService.failed(RuntimeException("outer", AuthenticationFailedException("535 bad credentials for admin@corp"))).code == "authentication_failed",
          DeliveryTestService.failed(RuntimeException("outer", UnknownHostException("smtp.nowhere.example"))).code == "unknown_host",
          DeliveryTestService.failed(RuntimeException("outer", SocketTimeoutException("read timed out"))).code == "timeout",
          DeliveryTestService.failed(RuntimeException("outer", ConnectException("refused"))).code == "connection_failed",
          DeliveryTestService.failed(RuntimeException("outer", SendFailedException("550 no such user"))).code == "rejected",
          DeliveryTestService.failed(RuntimeException("something else entirely")).code == "send_failed",
        )
      },
      test("never carries the text of the underlying error") {
        val failed = DeliveryTestService.failed(RuntimeException("outer", AuthenticationFailedException("535 bad credentials for admin@corp")))
        assertTrue(!failed.message.contains("535"), !failed.message.contains("corp"))
      },
    ),
  ).provide(TestClient.layer, Scope.default) @@ silentLogging @@ withLiveClock
