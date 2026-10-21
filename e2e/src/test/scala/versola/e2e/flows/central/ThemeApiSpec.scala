package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** Themes on central's admin API: a theme is a preset, five values, the forms' variables it sets one by one and its own
  * CSS. These tests check what is accepted and stored, that an unknown variable or a value that could leave its
  * declaration is refused with the reason (and nothing is stored), and that the stylesheet central renders puts the
  * token overrides after the five values and the theme's CSS after them both, so each later part wins.
  */
object ThemeApiSpec extends CentralApiSpec:

  private val path = "/configuration/themes"

  private def theme(id: String, variables: Option[Json.Obj], css: String = ""): Json =
    Json.Obj(
      "id" -> Json.Str(id),
      "css" -> Json.Str(css),
      "tokens" -> Json.Obj(
        (Seq("base" -> (Json.Str("studio"): Json)) ++ variables.map("variables" -> (_: Json)))*,
      ),
    )

  private def vars(entries: (String, String)*): Json.Obj =
    Json.Obj(entries.map((name, value) => name -> (Json.Str(value): Json))*)

  private def stored(central: CentralApi, id: String, expect: Boolean): Task[Boolean] =
    eventually(central.get(path, "tenantId" -> Fixtures.suiteTenant).flatMap(_.items("themes")).map(_.exists(_.str("id").contains(id))))(_ == expect)

  def spec = suite("Central API: themes")(
    test("a theme with token overrides is stored and listed with them") {
      for
        central <- api
        id <- CentralApi.id("probe-theme")
        created <- central.post(path, theme(id, Some(vars("--rs" -> "8px", "--brand" -> "#112233"))))
        variables <- eventually(
          central.get(path, "tenantId" -> Fixtures.suiteTenant).flatMap(_.items("themes")).map: themes =>
            themes.find(_.str("id").contains(id)).flatMap(_.obj("tokens")).flatMap(_.obj("variables")),
        )(_.isDefined)
      yield assertTrue(
        created.status == Status.Created,
        variables.flatMap(_.str("--rs")).contains("8px"),
        variables.flatMap(_.str("--brand")).contains("#112233"),
      )
    },
    test("a theme stored without them still works") {
      for
        central <- api
        id <- CentralApi.id("probe-theme")
        created <- central.post(path, theme(id, None))
        listed <- stored(central, id, expect = true)
      yield assertTrue(created.status == Status.Created, listed)
    },
    test("a variable outside the theming API is refused with its name, and nothing is stored") {
      for
        central <- api
        id <- CentralApi.id("probe-theme")
        created <- central.post(path, theme(id, Some(vars("--page-ink" -> "#000"))))
        message <- created.obj.map(_.str("message"))
        listed <- stored(central, id, expect = false)
      yield assertTrue(created.status == Status.BadRequest, message.exists(_.contains("--page-ink")), !listed)
    },
    test("a value that could leave its declaration is refused on create and on update") {
      for
        central <- api
        id <- CentralApi.id("probe-theme")
        created <- central.post(path, theme(id, Some(vars("--rs" -> "8px; } body { display: none"))))
        _ <- central.post(path, theme(id, None))
        updated <- central.put(path, theme(id, Some(vars("--brand" -> "url(https://x.example/a.png)"))))
        message <- updated.obj.map(_.str("message"))
      yield assertTrue(created.status == Status.BadRequest, updated.status == Status.BadRequest, message.exists(_.startsWith("--brand ")))
    },
    test("the stylesheet central renders puts the token overrides after the five values and the theme's CSS last") {
      for
        central <- api
        rendered <- central.post(
          path + "/css",
          Json.Obj(
            "tokens" -> Json.Obj("base" -> Json.Str("studio"), "variables" -> vars("--rs" -> "8px")),
            "css" -> Json.Str(".custom-rule { top: 0; }"),
          ),
        )
        css <- rendered.stringAt("css")
        values = css.indexOf("--brand:#ff6b4a")
        overrides = css.indexOf("--rs:8px;")
      yield assertTrue(
        rendered.status == Status.Ok,
        values >= 0,
        values < overrides,
        overrides < css.indexOf(".custom-rule"),
        css.lastIndexOf("--rs:") == overrides,
      )
    },
    test("rendering refuses an unsafe token override") {
      for
        central <- api
        rendered <- central.post(path + "/css", Json.Obj("tokens" -> Json.Obj("base" -> Json.Str("studio"), "variables" -> vars("--rs" -> "8px;}"))))
      yield assertTrue(rendered.status == Status.BadRequest)
    },
  )
