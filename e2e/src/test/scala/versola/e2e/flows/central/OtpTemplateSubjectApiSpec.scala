package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** The subject of an email template on central's admin API (#867): stored per language next to the body, read back,
  * and replaced by a later save. A save from a client that does not send `subjects` keeps the stored ones (an older
  * client must not lose them), and an empty map clears them. Whether the subject reaches a message is the unit
  * specs' ground: outside production auth sends no email, so the staged stack cannot show one arrive.
  */
object OtpTemplateSubjectApiSpec extends CentralApiSpec:

  private val path = "/configuration/challenges/otp-templates"

  private def template(id: String, channel: String, body: String, subjects: Option[Json.Obj]): Json =
    Json.Obj(
      (Seq[(String, Json)](
        "id" -> Json.Str(id),
        "tenantId" -> Json.Str(Fixtures.suiteTenant),
        "localizations" -> Json.Obj("en" -> Json.Str(body)),
        "purpose" -> Json.Str("otp"),
        "channel" -> Json.Str(channel),
      ) ++ subjects.map("subjects" -> (_: Json)))*,
    )

  private val html = "<html><body><p>Your code is {{code}}</p></body></html>"

  private def subjectsOf(central: CentralApi, id: String)(condition: Option[Json.Obj] => Boolean): Task[Option[Json.Obj]] =
    eventually(
      central.get(path, "tenantId" -> Fixtures.suiteTenant).flatMap(_.items("templates")).map: templates =>
        templates.find(_.str("id").contains(id)).flatMap(_.obj("subjects")),
    )(condition)

  private def remove(central: CentralApi, id: String, channel: String): UIO[Unit] =
    central
      .deleteWithBody(path, Json.Obj("id" -> Json.Str(id), "tenantId" -> Json.Str(Fixtures.suiteTenant), "purpose" -> Json.Str("otp"), "channel" -> Json.Str(channel)))
      .unit
      .catchAllCause(cause => ZIO.logWarningCause(s"Could not remove template $id after a test", cause))

  def spec = suite("Central API: email template subjects")(
    test("a subject per language is stored with the template and replaced by the next save") {
      for
        central <- api
        id <- CentralApi.id("probe-subject")
        saved <- central.put(path, template(id, "email", html, Some(Json.Obj("en" -> Json.Str("{{code}} is your code for {{appName}}")))))
        first <- subjectsOf(central, id)(_.isDefined)
        replaced <- central.put(path, template(id, "email", html, Some(Json.Obj("en" -> Json.Str("Your code")))))
        second <- subjectsOf(central, id)(_.flatMap(_.str("en")).contains("Your code"))
        _ <- remove(central, id, "email")
      yield assertTrue(
        saved.status == Status.NoContent,
        first.flatMap(_.str("en")).contains("{{code}} is your code for {{appName}}"),
        replaced.status == Status.NoContent,
        second.flatMap(_.str("en")).contains("Your code"),
      )
    },
    test("a save without subjects, as an older client sends it, keeps them; an empty map clears them") {
      for
        central <- api
        id <- CentralApi.id("probe-subject")
        _ <- central.put(path, template(id, "email", html, Some(Json.Obj("en" -> Json.Str("Your code")))))
        _ <- subjectsOf(central, id)(_.isDefined)
        edited = "<html><body><p>Use {{code}}</p></body></html>"
        saved <- central.put(path, template(id, "email", edited, None))
        kept <- eventually(
          central.get(path, "tenantId" -> Fixtures.suiteTenant).flatMap(_.items("templates")).map(_.find(_.str("id").contains(id))),
        )(_.exists(_.obj("localizations").flatMap(_.str("en")).contains(edited)))
        cleared <- central.put(path, template(id, "email", edited, Some(Json.Obj())))
        afterClear <- subjectsOf(central, id)(_.exists(_.fields.isEmpty))
        _ <- remove(central, id, "email")
      yield assertTrue(
        saved.status == Status.NoContent,
        kept.flatMap(_.obj("subjects")).flatMap(_.str("en")).contains("Your code"),
        cleared.status == Status.NoContent,
        afterClear.exists(_.fields.isEmpty),
      )
    },
    test("a subject that is blank, has a line break, or is on an SMS template is refused, and nothing is stored") {
      for
        central <- api
        id <- CentralApi.id("probe-subject")
        blank <- central.put(path, template(id, "email", html, Some(Json.Obj("en" -> Json.Str("  ")))))
        multiline <- central.put(path, template(id, "email", html, Some(Json.Obj("en" -> Json.Str("Code\r\nBcc: evil@example.test")))))
        sms <- central.put(path, template(id, "sms", "Your code is {{code}}", Some(Json.Obj("en" -> Json.Str("Your code")))))
        listed <- central.get(path, "tenantId" -> Fixtures.suiteTenant).flatMap(_.items("templates")).map(_.exists(_.str("id").contains(id)))
      yield assertTrue(
        blank.status == Status.BadRequest,
        multiline.status == Status.BadRequest,
        sms.status == Status.BadRequest,
        !listed,
      )
    },
  ) @@ TestAspect.sequential
