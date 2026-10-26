package versola.central.configuration.forms

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object FormPreviewsSpec extends ZIOSpecDefault:
  // The forms central seeds; `auth-settings` is the account page, which has no step to preview.
  private val builtInForms = Set(
    "credential",
    "otp",
    "proof",
    "password",
    "set-password",
    "consent",
    "passkey-enroll",
    "access-denied",
    "conversation-expired",
    "service-unavailable",
    "signed-out",
    "confirm-logout",
  )

  /** The checkout's root: tests run from it, or from `central/` when forked. */
  private def buildRoot: Path =
    Iterator.iterate(
      Path.of("").toAbsolutePath,
    )(_.getParent).takeWhile(_ != null).find(dir => Files.exists(dir.resolve("build.sbt"))).getOrElse(Path.of("").toAbsolutePath)

  private def field(step: Json, name: String): Option[Json] = step match
    case Json.Obj(fields) => fields.collectFirst { case (`name`, value) => value }
    case _ => None

  def spec = suite("FormPreviews")(
    test("covers every built-in form with a step, each once") {
      assertTrue(
        FormPreviews.all.map(_.formId).toSet == builtInForms,
        FormPreviews.all.map(_.formId).distinct.size == FormPreviews.all.size,
      )
    },
    test("gives every form at least one scenario, with an id that is unique within the form and a title in English and Russian") {
      assertTrue(
        FormPreviews.all.forall(_.scenarios.nonEmpty),
        FormPreviews.all.forall(form => form.scenarios.map(_.id).distinct.size == form.scenarios.size),
        FormPreviews.all.flatMap(_.scenarios).forall(scenario =>
          scenario.title.get("en").exists(_.nonEmpty) && scenario.title.get("ru").exists(_.nonEmpty),
        ),
      )
    },
    test("tags each step with the id of its own form, which is how the form reads it") {
      val mismatched =
        for
          form <- FormPreviews.all
          scenario <- form.scenarios
          if !field(scenario.step, "type").contains(Json.Str(form.formId))
        yield s"${form.formId}/${scenario.id}"
      assertTrue(mismatched.isEmpty)
    },
    test("shows the openid scope first on the consent screen and never lets the user drop it") {
      val checks =
        for
          form <- FormPreviews.all if form.formId == "consent"
          scenario <- form.scenarios
        yield
          val scopes = field(scenario.step, "scopes").collect { case Json.Arr(values) => values.toList }.getOrElse(Nil)
          val first = scopes.headOption.flatMap(scope => field(scope, "scope"))
          val deselectable = scopes.headOption.flatMap(scope => field(scope, "deselectable"))
          first.contains(Json.Str("openid")) && deselectable.contains(Json.Bool(false))
      assertTrue(checks.nonEmpty, checks.forall(identity))
    },
    test("matches the golden file that the console's tests render through the real forms") {
      // Regenerate with UPDATE_FORM_PREVIEWS=1 after changing a scenario; the console tests then render
      // the new states, so a step the forms cannot show fails there.
      for
        root <- ZIO.attemptBlocking(buildRoot)
        golden = root.resolve("central/src/test/resources/form-previews.golden.json")
        _ <- ZIO.when(sys.env.contains("UPDATE_FORM_PREVIEWS")):
          ZIO.attemptBlocking(Files.writeString(golden, FormPreviews.response.toJsonPretty + "\n", StandardCharsets.UTF_8))
        stored <- ZIO.attemptBlocking(Files.readString(golden, StandardCharsets.UTF_8))
      yield assertTrue(stored.fromJson[GetFormPreviewsResponse] == Right(FormPreviews.response))
    },
    test("round-trips through the JSON the console reads") {
      val decoded = FormPreviews.response.toJson.fromJson[GetFormPreviewsResponse]
      assertTrue(decoded == Right(FormPreviews.response))
    },
  )
