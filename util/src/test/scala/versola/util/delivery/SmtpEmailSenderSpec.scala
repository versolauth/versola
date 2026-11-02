package versola.util.delivery

import versola.util.Email
import zio.*
import zio.test.*
import zio.test.TestAspect.{silentLogging, timeout, withLiveClock}

object SmtpEmailSenderSpec extends ZIOSpecDefault:

  private def server: ZIO[Scope, Nothing, FakeSmtpServer] =
    ZIO.acquireRelease(ZIO.succeed(FakeSmtpServer.start()))(server => ZIO.succeed(server.close()))

  private def settings(port: Int, password: Option[String] = Some("mail-secret")) =
    SmtpSettings(
      host = "localhost",
      port = port,
      username = "mailer",
      password = password,
      from = Email("noreply@example.com"),
      subject = "Your code",
      startTls = false,
    )

  def spec = suite("SmtpEmailSender")(
    test("hands the message to the server with the configured sender, subject and credentials") {
      for
        smtp <- server
        _ <- EmailSender.smtp(settings(smtp.port)).send(Email("user@example.com"), "<p>Your code is 123456</p>")
        message <- ZIO.fromOption(smtp.messages.headOption).orElseFail(RuntimeException("the server received nothing"))
      yield assertTrue(
        message.from == "noreply@example.com",
        message.recipients == List("user@example.com"),
        message.header("Subject").contains("Your code"),
        message.header("Content-Type").exists(_.startsWith("text/html")),
        message.body.contains("Your code is 123456"),
        message.user.contains("mailer"),
        message.password.contains("mail-secret"),
      )
    },
    test("does not authenticate when no password is configured") {
      for
        smtp <- server
        _ <- EmailSender.smtp(settings(smtp.port, password = None)).send(Email("user@example.com"), "hello")
        message <- ZIO.fromOption(smtp.messages.headOption).orElseFail(RuntimeException("the server received nothing"))
      yield assertTrue(message.user.isEmpty, message.password.isEmpty)
    },
    test("fails when the server cannot be reached") {
      for
        smtp <- server
        port = smtp.port
        _ <- ZIO.succeed(smtp.close())
        result <- EmailSender.smtp(settings(port)).send(Email("user@example.com"), "hello").either
      yield assertTrue(result.isLeft)
    },
    test("gives up on a server that accepts the connection and never answers") {
      for
        silent <- ZIO.acquireRelease(ZIO.succeed(FakeSmtpServer.startSilent()))(server => ZIO.succeed(server.close()))
        result <- EmailSender.smtp(settings(silent.port)).send(Email("user@example.com"), "hello").either
      yield assertTrue(result.isLeft)
    } @@ timeout(30.seconds),
    test("does not print the password in its settings") {
      assertTrue(!settings(25).toString.contains("mail-secret"))
    },
  ) @@ silentLogging @@ withLiveClock
