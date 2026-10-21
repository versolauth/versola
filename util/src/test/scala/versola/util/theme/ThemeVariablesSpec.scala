package versola.util.theme

import zio.json.*
import zio.test.*

object ThemeVariablesSpec extends ZIOSpecDefault:

  def spec = suite("ThemeVariables")(
    test("allows the theming API of the forms, and only it") {
      assertTrue(
        ThemeVariables.Allowed.map(_._1) == Vector(
          "--brand", "--on-brand", "--focus", "--on-focus", "--rf", "--rb", "--rs",
          "--page-light", "--page-dark", "--glow-light", "--glow-dark", "--control-h",
        ),
      )
    },
    test("every allowed variable is declared in forms/common.css") {
      val css = ThemeCss.baseStylesheet
      assertTrue(ThemeVariables.Allowed.forall((name, _) => css.contains(s"$name:")))
    },
    test("accepts colours, lengths and plain values") {
      val values = Map(
        "--brand" -> "#ff6b4a",
        "--on-brand" -> "rgb(0 0 0 / 50%)",
        "--rs" -> "8px",
        "--page-light" -> "color-mix(in oklab, red 20%, white)",
        "--control-h" -> "3.5rem",
      )
      assertTrue(ThemeVariables.validate(values) == Right(()))
    },
    test("refuses a variable that is not in the theming API, naming it") {
      val result = ThemeVariables.validate(Map("--page-ink" -> "#000"))
      assertTrue(result.left.exists(message => message.contains("--page-ink") && message.contains("allowed")))
    },
    suite("refuses a value that could leave its declaration")(
      Seq("red; } body { display: none", "red}", "{", "url(https://x.example/a.png)", "URL(x)", "expression(alert(1))", "red /* x */", "red !important", "\\41", "@import", "", "   ", "a" * 200, "rgb(1,2,3")
        .map(value => test(s"'${value.take(30)}'") {
          assertTrue(ThemeVariables.validate(Map("--brand" -> value)).left.exists(_.startsWith("--brand ")))
        })*,
    ),
    test("renders one :root block in the order of the API, trimmed") {
      val css = ThemeVariables.render(Map("--rs" -> " 8px ", "--brand" -> "#112233"))
      assertTrue(css == "/* token overrides */\n:root{--brand:#112233;--rs:8px;}")
    },
    test("renders nothing for no variables, and drops what it would refuse") {
      assertTrue(
        ThemeVariables.render(Map.empty[String, String]) == "",
        ThemeVariables.render(Map("--brand" -> "red; }", "--nope" -> "1px")) == "",
      )
    },
    test("tokens carry the variables in JSON, and a theme saved without them reads back with none") {
      val tokens = ThemeTokens(base = "studio", overrides = None, variables = Some(Map("--rs" -> "8px")))
      assertTrue(
        tokens.toJson == """{"base":"studio","variables":{"--rs":"8px"}}""",
        tokens.toJson.fromJson[ThemeTokens] == Right(tokens),
        """{"base":"studio","overrides":{}}""".fromJson[ThemeTokens].map(_.variables) == Right(None),
      )
    },
  )
