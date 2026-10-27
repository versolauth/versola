package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.sequential

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Delivery settings saved in central reach auth and are used for the next message (#832): the path from the console's
  * API to a mail in an inbox, through the cache and the sync, which no unit test sees whole.
  *
  * The stack runs with `deliver-outside-prod = true` (`gen-env` writes it for the `local` target): outside production
  * auth otherwise sends nothing at all. The receiving ends are a mail server and an SMS provider written in test code
  * ([[SmtpSink]], [[SmsStub]]), listening on the loopback interface the stack shares with this test.
  *
  * Each test saves what it needs, syncs auth (`POST /service/configuration/sync`, non-prod, which exists so that a test
  * need not wait out a refresh interval) and removes the record at the end, so the stack is left without one. The record
  * is global, which is why the tests run one after another.
  */
object DeliveryFlowSpec extends CentralApiSpec:

  private val path = "/configuration/delivery-settings"

  private def smtpBlock(port: Int, subject: String = "Delivery check", enabled: Boolean = true): Json.Obj =
    Json.Obj(
      "host" -> Json.Str("localhost"),
      "port" -> Json.Num(port),
      "username" -> Json.Str("mailer"),
      "from" -> Json.Str("noreply@example.test"),
      "subject" -> Json.Str(subject),
      "startTls" -> Json.Bool(false),
      "enabled" -> Json.Bool(enabled),
    )

  private def smsBlock(port: Int, enabled: Boolean = true): Json.Obj =
    Json.Obj(
      "method" -> Json.Str("POST"),
      "url" -> Json.Str(s"http://localhost:$port/send"),
      "username" -> Json.Str("sms-account"),
      "body" -> Json.Obj("phones" -> Json.Str("{{phone}}"), "mes" -> Json.Str("{{message}}")),
      "enabled" -> Json.Bool(enabled),
    )

  private def save(central: CentralApi, fields: (String, Json)*): Task[ApiResult] =
    central.put(path, Json.Obj(fields*)).tap(result =>
      ZIO.fail(RuntimeException(s"saving failed: ${result.status} ${result.body}")).unless(result.status == Status.NoContent),
    )

  /** Auth reloads its caches now. Through central, the way every e2e spec does it. */
  private def syncAuth(central: CentralApi): Task[Unit] =
    central.postEmpty("/service/configuration/sync").flatMap: result =>
      ZIO.fail(RuntimeException(s"syncing auth failed: ${result.status} ${result.body}")).unless(result.status.isSuccess).unit

  /** Removes the record and syncs, whatever the test did: a record left behind would send every later spec's codes to a
    * server that is gone. A cleanup that fails is logged, with what to remove by hand, and does not hide the test's own result. */
  private def cleanUp(central: CentralApi): UIO[Unit] =
    (central.delete(path) *> syncAuth(central))
      .catchAllCause(cause => ZIO.logWarningCause(s"Could not remove the delivery settings after a test: DELETE $path and sync auth by hand", cause))

  private def createUser(central: CentralApi, email: Option[String] = None, phone: Option[String] = None): Task[String] =
    for
      created <- central.post("/users", Fixtures.user(email = email, phone = phone))
      id <- created.stringAt("id")
      _ <- central.flushUserOutbox
    yield id

  private def resetPassword(central: CentralApi, userId: String, channel: String): Task[ApiResult] =
    central.post(
      "/users/password/reset",
      Json.Obj("userId" -> Json.Str(userId), "expiresInSeconds" -> Json.Num(3600), "channel" -> Json.Str(channel)),
    )

  /** The mail as the recipient reads it: a message body is quoted-printable, which cuts long lines with `=` and writes `=`
    * itself as `=3D`. */
  private def decoded(body: String): String =
    body.replace("=\n", "").replace("=3D", "=")

  /** The temporary password in the default email template: the only 16-character word in a block of its own. */
  private def passwordIn(body: String): Option[String] =
    ">([^<\\s]{16})<".r.findFirstMatchIn(decoded(body)).map(_.group(1))

  def spec = suite("Delivery settings reach auth")(
    test("a temporary password is mailed through the SMTP server saved in central once auth has synced") {
      for
        central <- api
        mailer <- SmtpSink.scoped
        email <- CentralApi.email("delivery")
        userId <- createUser(central, email = Some(email))
        _ <- save(central, "smtp" -> smtpBlock(mailer.port, subject = "Your temporary password"), "smtpPassword" -> Json.Str("mail-s3cret"))
          .zipRight(syncAuth(central))
          .zipRight(resetPassword(central, userId, "email").flatMap(result =>
            ZIO.fail(RuntimeException(s"reset failed: ${result.status} ${result.body}")).unless(result.status == Status.NoContent),
          ))
          .ensuring(cleanUp(central))
        mails <- mailer.awaitMessages(1)
        mail = mails.head
      yield assertTrue(
        mails.length == 1,
        mail.recipients == List(email),
        mail.from == "noreply@example.test",
        mail.header("Subject").contains("Your temporary password"),
        mail.user.contains("mailer"),
        // The password went from the console's API through central's cache and the sync to the SMTP client, decrypted.
        mail.password.contains("mail-s3cret"),
        passwordIn(mail.body).isDefined,
      )
    },
    test("an SMS provider saved in central receives the text, with its credentials as HTTP Basic") {
      for
        central <- api
        provider <- SmsStub.scoped
        phone <- CentralApi.phone
        userId <- createUser(central, phone = Some(phone))
        _ <- (save(central, "otpProvider" -> smsBlock(provider.port), "otpProviderPassword" -> Json.Str("sms-s3cret"))
          .zipRight(syncAuth(central))
          .zipRight(resetPassword(central, userId, "sms"))).ensuring(cleanUp(central))
        requests <- provider.awaitRequests(1)
        request = requests.head
        basic = Base64.getEncoder.encodeToString("sms-account:sms-s3cret".getBytes(StandardCharsets.UTF_8))
      yield assertTrue(
        request.method == "POST",
        request.path == "/send",
        request.authorization.contains(s"Basic $basic"),
        request.contentType.exists(_.startsWith("application/json")),
        request.body.contains(phone),
        request.body.contains("temporary password"),
      )
    },
    test("a test message goes through the block as edited, and a closed port is reported without anything the system said") {
      for
        central <- api
        mailer <- SmtpSink.scoped
        email <- CentralApi.email("delivery")
        delivered <- central.post(
          s"$path/test",
          Json.Obj(
            "channel" -> Json.Str("email"),
            "to" -> Json.Str(email),
            "smtp" -> smtpBlock(mailer.port, subject = "Test from the console"),
            "password" -> Json.Str("typed-s3cret"),
          ),
        ).flatMap(_.obj)
        mails <- mailer.awaitMessages(1)
        closed <- central.post(
          s"$path/test",
          Json.Obj("channel" -> Json.Str("email"), "to" -> Json.Str(email), "smtp" -> smtpBlock(1)),
        ).flatMap(result => result.obj.map(result.status -> _))
      yield assertTrue(
        delivered.bool("delivered").contains(true),
        mails.head.recipients == List(email),
        mails.head.header("Subject").contains("Test from the console"),
        closed._1 == Status.Ok,
        closed._2.bool("delivered").contains(false),
        closed._2.str("code").exists(Set("connection_failed", "timeout").contains),
      )
    },
    test("a saved password is not sent to another server, in a test or in a save, and nothing is sent or changed") {
      for
        central <- api
        mailer <- SmtpSink.scoped
        email <- CentralApi.email("delivery")
        outcome <- (for
          _ <- save(central, "smtp" -> smtpBlock(mailer.port), "smtpPassword" -> Json.Str("mail-s3cret"))
          refusedTest <- central.post(
            s"$path/test",
            Json.Obj("channel" -> Json.Str("email"), "to" -> Json.Str(email), "smtp" -> smtpBlock(mailer.port + 1)),
          )
          refusedSave <- central.put(path, Json.Obj("smtp" -> smtpBlock(mailer.port + 1)))
          read <- central.get(path).flatMap(_.obj)
        yield (refusedTest, refusedSave, read)).ensuring(cleanUp(central))
        (refusedTest, refusedSave, read) = outcome
        mails <- mailer.messages
      yield assertTrue(
        refusedTest.status == Status.BadRequest,
        refusedTest.body.contains("password must be entered again"),
        refusedSave.status == Status.BadRequest,
        refusedSave.body.contains("password must be entered again"),
        mails.isEmpty,
        // The refused save changed nothing: the server on record is still the first one.
        read.obj("smtp").flatMap(_.int("port")).contains(mailer.port),
      )
    },
  ) @@ sequential
