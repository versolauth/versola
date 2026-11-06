package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** Delivery settings on central's admin API (#832): the SMTP server and the SMS provider auth delivers codes and
  * temporary passwords through. One record per deployment, so the tests here share it, run one after another,
  * each starts by storing what it needs, and the last one deletes the record so the stack is left as it was found.
  * What is checked is the round trip through edge's proxy and central's cache, and the rule that makes the console
  * workable: a stored password is never returned, a save without one keeps it, and an empty one clears it.
  */
object DeliverySettingsApiSpec extends CentralApiSpec:

  private val path = "/configuration/delivery-settings"

  private def smtp(subject: String = "Your code"): Json.Obj =
    Json.Obj(
      "host" -> Json.Str("smtp.example.com"),
      "port" -> Json.Num(587),
      "username" -> Json.Str("mailer"),
      "from" -> Json.Str("noreply@example.com"),
      "subject" -> Json.Str(subject),
      "startTls" -> Json.Bool(true),
      "enabled" -> Json.Bool(true),
    )

  private val otpProvider: Json.Obj =
    Json.Obj(
      "method" -> Json.Str("POST"),
      "url" -> Json.Str("https://sms.example.com/send"),
      "body" -> Json.Obj("phones" -> Json.Str("{{phone}}"), "mes" -> Json.Str("{{message}}")),
      "enabled" -> Json.Bool(true),
    )

  private def withField(block: Json.Obj, name: String, value: Json): Json.Obj =
    Json.Obj(block.fields.map((field, current) => field -> (if field == name then value else current))*)

  private def update(fields: (String, Json)*): Json = Json.Obj(fields*)

  /** Reads until central's cache reflects the write: the record as the console sees it. */
  private def settings(central: CentralApi)(condition: Json.Obj => Boolean): Task[Json.Obj] =
    eventually(central.get(path).flatMap(_.obj))(condition)

  def spec = suite("Central API: delivery settings")(
    test("both blocks are stored and read back, with the password replaced by a flag") {
      for
        central <- api
        saved <- central.put(path, update("smtp" -> smtp(), "smtpPassword" -> Json.Str("mail-s3cret"), "otpProvider" -> otpProvider))
        read <- settings(central)(_.obj("smtp").flatMap(_.str("subject")).contains("Your code"))
        raw <- central.get(path).map(_.body)
      yield assertTrue(
        saved.status == Status.NoContent,
        read.obj("smtp").flatMap(_.str("host")).contains("smtp.example.com"),
        read.bool("hasSmtpPassword").contains(true),
        read.obj("otpProvider").flatMap(_.str("url")).contains("https://sms.example.com/send"),
        read.bool("hasOtpProviderPassword").contains(false),
        !raw.contains("mail-s3cret"),
      )
    },
    test("a save without the password keeps the stored one; an empty password clears it") {
      for
        central <- api
        _ <- central.put(path, update("smtp" -> smtp(), "smtpPassword" -> Json.Str("mail-s3cret")))
        _ <- settings(central)(_.bool("hasSmtpPassword").contains(true))
        kept <- central.put(path, update("smtp" -> smtp("Changed subject")))
        afterKeep <- settings(central)(_.obj("smtp").flatMap(_.str("subject")).contains("Changed subject"))
        cleared <- central.put(path, update("smtp" -> smtp("Changed subject"), "smtpPassword" -> Json.Str("")))
        afterClear <- settings(central)(_.bool("hasSmtpPassword").contains(false))
      yield assertTrue(
        kept.status == Status.NoContent,
        afterKeep.bool("hasSmtpPassword").contains(true),
        cleared.status == Status.NoContent,
        afterClear.obj("smtp").flatMap(_.str("subject")).contains("Changed subject"),
      )
    },
    test("a block that is left out is removed with its password") {
      for
        central <- api
        _ <- central.put(path, update("smtp" -> smtp(), "smtpPassword" -> Json.Str("mail-s3cret"), "otpProvider" -> otpProvider))
        _ <- settings(central)(_.bool("hasSmtpPassword").contains(true))
        _ <- central.put(path, update("otpProvider" -> otpProvider))
        read <- settings(central)(_.obj("smtp").isEmpty)
      yield assertTrue(read.bool("hasSmtpPassword").contains(false), read.obj("otpProvider").isDefined)
    },
    test("an unusable block is refused with the reason, and nothing changes") {
      for
        central <- api
        _ <- central.put(path, update("smtp" -> smtp("Before")))
        _ <- settings(central)(_.obj("smtp").flatMap(_.str("subject")).contains("Before"))
        badFrom <- central.put(path, update("smtp" -> withField(block = smtp("After"), name = "from", value = Json.Str("not-an-address"))))
        badUrl <- central.put(path, update("otpProvider" -> withField(block = otpProvider, name = "url", value = Json.Str("sms.example.com"))))
        credentialsInUrl <- central.put(
          path,
          update("otpProvider" -> withField(block = otpProvider, name = "url", value = Json.Str("https://sms:topsecret@sms.example.com/send"))),
        )
        // Not polled: a refused write changes nothing, so there is no later state to wait for, and the read
        // shows what the record held before the three attempts.
        read <- central.get(path).flatMap(_.obj)
      yield assertTrue(
        badFrom.status == Status.BadRequest,
        badFrom.body.contains("smtp.from"),
        badUrl.status == Status.BadRequest,
        badUrl.body.contains("otpProvider.url"),
        credentialsInUrl.status == Status.BadRequest,
        credentialsInUrl.body.contains("otpProvider.url"),
        !credentialsInUrl.body.contains("topsecret"),
        read.obj("smtp").flatMap(_.str("subject")).contains("Before"),
      )
    },
    test("an anonymous caller can neither read, save nor delete the settings") {
      for
        central <- api
        read <- central.anonymous.get(path)
        saved <- central.anonymous.put(path, update("smtp" -> smtp()))
        deleted <- central.anonymous.delete(path)
      yield assertTrue(read.status == Status.Unauthorized, saved.status == Status.Unauthorized, deleted.status == Status.Unauthorized)
    },
    // Last on purpose: it is also the clean-up, so no later spec on this stack finds a record that points
    // auth at smtp.example.com.
    test("deleting the record returns the deployment to nothing stored") {
      for
        central <- api
        _ <- central.put(path, update("smtp" -> smtp(), "smtpPassword" -> Json.Str("mail-s3cret")))
        _ <- settings(central)(_.bool("hasSmtpPassword").contains(true))
        deleted <- central.delete(path)
        read <- settings(central)(_.obj("smtp").isEmpty)
      yield assertTrue(
        deleted.status == Status.NoContent,
        read.bool("hasSmtpPassword").contains(false),
        read.obj("otpProvider").isEmpty,
        read.bool("hasOtpProviderPassword").contains(false),
      )
    },
  ) @@ TestAspect.sequential
