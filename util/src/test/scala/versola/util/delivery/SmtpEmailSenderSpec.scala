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
        _ <- EmailSender.smtp(settings(smtp.port)).send(Email("user@example.com"), None, "<p>Your code is 123456</p>")
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
    test("sends the subject of the message when it has one, and the settings' subject otherwise") {
      for
        smtp <- server
        sender = EmailSender.smtp(settings(smtp.port))
        _ <- sender.send(Email("user@example.com"), Some("Code for Acme"), "hello")
        _ <- sender.send(Email("user@example.com"), None, "hello")
        subjects = smtp.messages.map(_.header("Subject"))
      yield assertTrue(subjects == List(Some("Code for Acme"), Some("Your code")))
    },
    test("sends a subject that is not ASCII as UTF-8 whatever the platform's default charset is") {
      for
        smtp <- server
        _ <- EmailSender.smtp(settings(smtp.port)).send(Email("user@example.com"), Some("Ваш код"), "hello")
        message <- ZIO.fromOption(smtp.messages.headOption).orElseFail(RuntimeException("the server received nothing"))
      yield assertTrue(message.header("Subject").exists(_.toLowerCase.startsWith("=?utf-8?")))
    },
    test("does not let a line break in a subject start another header") {
      for
        smtp <- server
        _ <- EmailSender.smtp(settings(smtp.port)).send(Email("user@example.com"), Some("Code\r\nBcc: evil@example.com"), "hello")
        message <- ZIO.fromOption(smtp.messages.headOption).orElseFail(RuntimeException("the server received nothing"))
      yield assertTrue(message.header("Subject").contains("Code  Bcc: evil@example.com"), message.header("Bcc").isEmpty)
    },
    test("does not authenticate when no password is configured") {
      for
        smtp <- server
        _ <- EmailSender.smtp(settings(smtp.port, password = None)).send(Email("user@example.com"), None, "hello")
        message <- ZIO.fromOption(smtp.messages.headOption).orElseFail(RuntimeException("the server received nothing"))
      yield assertTrue(message.user.isEmpty, message.password.isEmpty)
    },
    test("refuses to go on in clear when STARTTLS is on and the server does not offer it") {
      for
        smtp <- server
        result <- EmailSender.smtp(settings(smtp.port).copy(startTls = true)).send(Email("user@example.com"), None, "hello").either
      yield assertTrue(result.isLeft, smtp.messages.isEmpty)
    },
    test("fails when the server cannot be reached") {
      for
        smtp <- server
        port = smtp.port
        _ <- ZIO.succeed(smtp.close())
        result <- EmailSender.smtp(settings(port)).send(Email("user@example.com"), None, "hello").either
      yield assertTrue(result.isLeft)
    },
    test("gives up on a server that accepts the connection and never answers") {
      for
        silent <- ZIO.acquireRelease(ZIO.succeed(FakeSmtpServer.startSilent()))(server => ZIO.succeed(server.close()))
        result <- EmailSender.smtp(settings(silent.port)).send(Email("user@example.com"), None, "hello").either
      yield assertTrue(result.isLeft)
    } @@ timeout(30.seconds),
    test("does not print the password in its settings") {
      assertTrue(!settings(25).toString.contains("mail-secret"))
    },
  ) @@ silentLogging @@ withLiveClock
