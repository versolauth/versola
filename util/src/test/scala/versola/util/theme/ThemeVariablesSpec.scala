package versola.util.theme

import versola.util.theme.VariableValue.{Pair, Single}
import zio.json.*
import zio.test.*

import java.nio.file.{Files, Paths}

object ThemeVariablesSpec extends ZIOSpecDefault:

  private def declared(css: String): Map[String, String] =
    val root = css.substring(css.indexOf(":root {") + 7, css.indexOf("\n}\n"))
    "--([a-z-]+):\\s*((?:[^;\"]|\"[^\"]*\")+);".r
      .findAllMatchIn(root)
      .map(found => s"--${found.group(1)}" -> found.group(2).trim.replaceAll("\\s+", " "))
      .toMap

  // Custom properties of common.css that are not a public token: the rest of this file is the plumbing.
  private val internal = Set("--scheme-locked", "--page", "--glow", "--page-bg", "--passkey-icon")

  private def problem(name: String, value: VariableValue): Option[String] =
    ThemeVariables.find(name).flatMap(ThemeVariables.valueProblem(_, value))

  private def refused(name: String, value: String) = problem(name, Single(value)).isDefined

  def spec = suite("ThemeVariables")(
    suite("the list")(
      test("names are unique, in a known group, with a description in en and ru and a start that passes its own rule") {
        val groups = ThemeVariables.Groups.map(_.id).toSet
        assertTrue(
          ThemeVariables.All.map(_.name).distinct.size == ThemeVariables.All.size,
          ThemeVariables.All.forall(variable => groups.contains(variable.group)),
          ThemeVariables.Groups.forall(group => ThemeVariables.All.exists(_.group == group.id) && group.title.keySet == Set("en", "ru")),
          ThemeVariables.All.forall(variable => variable.description.keySet == Set("en", "ru") && variable.description.values.forall(_.nonEmpty)),
          ThemeVariables.All.forall(variable => ThemeVariables.valueProblem(variable, variable.start).isEmpty),
          ThemeVariables.All.forall(variable => (variable.start.isInstanceOf[Pair]) == variable.pair),
          ThemeVariables.All.filter(_.pair).forall(_.kind == VariableKind.colour),
        )
      },
      test("is every custom property of common.css :root that is public, and only those") {
        val inCss = declared(ThemeCss.baseStylesheet).keySet -- internal
        assertTrue(inCss == ThemeVariables.All.map(_.name).toSet)
      },
      test("each default is the value common.css declares") {
        val inCss = declared(ThemeCss.baseStylesheet)
        val mismatched =
          ThemeVariables.All.filter(variable => !inCss.get(variable.name).contains(variable.default.replaceAll("\\s+", " "))).map(_.name)
        assertTrue(mismatched == Vector.empty[String])
      },
      test("keeps the names the theming API had before, in the same relative order") {
        val old = Vector(
          "--brand",
          "--on-brand",
          "--focus",
          "--on-focus",
          "--rf",
          "--rb",
          "--rs",
          "--page-light",
          "--page-dark",
          "--glow-light",
          "--glow-dark",
          "--control-h",
        )
        assertTrue(old.forall(name => ThemeVariables.find(name).isDefined))
      },
      test("has the button, card and field tokens an operator restyles first") {
        val names = ThemeVariables.All.map(_.name).toSet
        assertTrue(
          Set(
            "--btn",
            "--on-btn",
            "--btn-hover",
            "--btn-secondary-line",
            "--btn-secondary-ink",
            "--sheet",
            "--sheet-line",
            "--shadow",
            "--field",
            "--field-hover",
            "--line",
            "--line-strong",
            "--ink",
            "--text-muted",
            "--danger",
            "--invert",
          ).subsetOf(names),
        )
      },
      test("serialises for the console: kind, pair, descriptions and a start that is a string or a pair") {
        val brand = ThemeVariables.find("--ink").get.toJson
        assertTrue(
          brand.contains("\"kind\":\"colour\""),
          brand.contains("\"pair\":true"),
          brand.contains("\"start\":{\"light\":\"#1c1b19\",\"dark\":\"#f3f2f0\"}"),
          ThemeVariables.find("--rs").get.toJson.contains("\"start\":\"24px\""),
        )
      },
    ),
    suite("the copy the console and the website read")(
      test("central-ui/src/utils/theme-variables.json is this list (UPDATE_THEME_VARIABLES_JSON=1 rewrites it)") {
        val file = Iterator.iterate(
          Paths.get(sys.props("user.dir")).toAbsolutePath,
        )(_.getParent).takeWhile(_ != null).map(_.resolve("central-ui/src/utils/theme-variables.json")).find(path => Files.exists(path.getParent))
        val path = file.get
        val expected = ThemeVariables.catalog.toJsonPretty + "\n"
        if sys.env.contains("UPDATE_THEME_VARIABLES_JSON") then Files.writeString(path, expected)
        assertTrue(Files.readString(path).fromJson[ThemeVariableCatalog] == Right(ThemeVariables.catalog), Files.readString(path) == expected)
      },
    ),
    suite("value rules per kind")(
      test("a colour is hex, a name, or a colour function") {
        assertTrue(
          Seq(
            "#fff",
            "#ff6b4a",
            "#ff6b4a80",
            "red",
            "transparent",
            "rgb(0 0 0 / 50%)",
            "rgba(0, 0, 0, 0.5)",
            "oklch(60% 0.2 30)",
            "color-mix(in oklab, var(--brand) 20%, white)",
          ).forall(value => !refused("--ink", value)),
          Seq(
            "12px",
            "#ff",
            "#ggg",
            "calc(1px)",
            "light-dark(red, blue)",
            "var(x)",
            "color-mix(in oklab, foo(1) 20%, white)",
            "red blue",
            "1",
          ).forall(refused("--ink", _)),
        )
      },
      test("a length is a number with a unit, or 0, and not negative") {
        assertTrue(
          Seq("8px", "0", "3.5rem", "1em", "90vw", "50%", "999px").forall(value => !refused("--rs", value)),
          Seq("red", "8", "-4px", "8 px", "calc(1px + 2px)", "8pt", "").forall(refused("--rs", _)),
        )
      },
      test("a weight is a whole number from 100 to 900") {
        assertTrue(
          Seq("100", "400", "750", "900").forall(value => !refused("--heading-weight", value)),
          Seq("99", "901", "bold", "7.5", "0700", "-100", "").forall(refused("--heading-weight", _)),
        )
      },
      test("a shadow is none, or layers of offsets, optional blur and spread, and a colour") {
        assertTrue(
          Seq(
            "none",
            "0 30px 60px -24px var(--shadow-color)",
            "0 0 0 1px #00000020",
            "0 2px 8px rgba(0, 0, 0, 0.2), inset 0 1px 0 white",
            "4px 4px red",
          ).forall(value => !refused("--shadow", value)),
          Seq(
            "red",
            "0 0",
            "0 0 0 0 0 red",
            "0 30px 60px -24px",
            "0 1px red blue",
            "0 0 0 foo(1)",
            "none, none",
            "0 0 inset inset red",
          ).forall(refused("--shadow", _)),
        )
      },
      test("a font is a list of names, with no quotes, urls or functions") {
        assertTrue(
          Seq("Georgia, serif", "system-ui, Segoe UI, sans-serif", "Arial").forall(value => !refused("--font", value)),
          Seq("'Georgia'", "\"x\"", "url(x)", "Georgia;", "a, ", "1px").forall(refused("--font", _)),
        )
      },
    ),
    suite("pairs")(
      test("a pair token takes one value, or a light and a dark one") {
        assertTrue(
          problem("--ink", Single("#111111")).isEmpty,
          problem("--ink", Pair("#111111", "#eeeeee")).isEmpty,
          problem("--field", Pair("#fff", "rgb(0 0 0 / 50%)")).isEmpty,
        )
      },
      test("a token that is not a pair refuses a pair, and a pair's parts must each pass the kind") {
        assertTrue(
          problem("--brand", Pair("#111111", "#eeeeee")).exists(_.contains("one value")),
          problem("--rs", Pair("8px", "9px")).isDefined,
          problem("--ink", Pair("#111111", "nope nope")).exists(_.startsWith("(dark)")),
          problem("--ink", Pair("12px", "#eeeeee")).exists(_.startsWith("(light)")),
          problem("--ink", Pair("red; }", "#eeeeee")).isDefined,
        )
      },
      test("renders a pair as light-dark(), and a single value as it is, so a theme is written in the order of the list") {
        val css = ThemeVariables.render(Map("--rs" -> Single(" 8px "), "--btn" -> Pair("#112233", "#ddeeff"), "--ink" -> Single("#000000")))
        assertTrue(css == "/* token overrides */\n:root{--rs:8px;--ink:#000000;--btn:light-dark(#112233,#ddeeff);}")
      },
      test("one value is written without light-dark(), for both schemes") {
        assertTrue(ThemeVariables.render(Map("--btn" -> Single("#112233"))) == "/* token overrides */\n:root{--btn:#112233;}")
      },
    ),
    suite("validation")(
      test("accepts every token's start, and a mix of kinds") {
        val values = Map[String, VariableValue](
          "--brand" -> Single("#ff6b4a"),
          "--on-brand" -> Single("rgb(0 0 0 / 50%)"),
          "--rs" -> Single("8px"),
          "--page-light" -> Single("color-mix(in oklab, red 20%, white)"),
          "--control-h" -> Single("3.5rem"),
          "--btn" -> Pair("#112233", "#ddeeff"),
          "--heading-weight" -> Single("600"),
        )
        assertTrue(
          ThemeVariables.validate(values) == Right(()),
          ThemeVariables.validate(ThemeVariables.All.map(variable => variable.name -> variable.start).toMap) == Right(()),
        )
      },
      test("refuses a variable that is not in the theming API, naming it") {
        val result = ThemeVariables.validate(Map("--page-bg" -> Single("#000000")))
        assertTrue(result.left.exists(message => message.contains("--page-bg") && message.contains("allowed")))
      },
      test("names the variable whose value is refused, and says why") {
        assertTrue(
          ThemeVariables.validate(Map("--rs" -> Single("red"))).left.exists(message => message.startsWith("--rs ") && message.contains("length")),
          ThemeVariables.validate(Map("--heading-weight" -> Single("1000"))).left.exists(_.startsWith("--heading-weight ")),
          ThemeVariables.validate(Map("--brand" -> Pair("#111111", "#222222"))).left.exists(_.startsWith("--brand ")),
        )
      },
      suite("refuses a value that could leave its declaration, whatever the kind")(
        Seq(
          "red; } body { display: none",
          "red}",
          "{",
          "url(https://x.example/a.png)",
          "URL(x)",
          "expression(alert(1))",
          "red /* x */",
          "red !important",
          "\\41",
          "@import",
          "",
          "   ",
          "a" * 200,
          "rgb(1,2,3",
        )
          .flatMap(value => Seq("--brand", "--rs", "--shadow", "--font", "--heading-weight").map(name => (name, value)))
          .map((name, value) =>
            test(s"$name: '${value.take(30)}'") {
              assertTrue(ThemeVariables.validate(Map(name -> Single(value))).left.exists(_.startsWith(s"$name ")))
            },
          )*,
      ),
      test("a hostile value in either half of a pair is refused") {
        assertTrue(
          Seq("--ink", "--btn", "--field").forall(name =>
            ThemeVariables.validate(Map(name -> Pair("#000000", "red;}body{x:y"))).isLeft && ThemeVariables.validate(Map(name -> Pair(
              "url(x)",
              "#000000",
            ))).isLeft,
          ),
        )
      },
    ),
    suite("rendering")(
      test("renders nothing for no variables, and drops what it would refuse") {
        assertTrue(
          ThemeVariables.render(Map.empty[String, VariableValue]) == "",
          ThemeVariables.render(Map("--brand" -> Single("red; }"), "--nope" -> Single("1px"), "--brand-x" -> Pair("red", "blue"))) == "",
          ThemeVariables.render(Map("--ink" -> Pair("red", "blue}"))) == "",
        )
      },
      test("is placed after the five values and before the custom CSS") {
        val tokens = ResolvedTokens(BrandColor.parse("#155e75").toOption.get, Shape.soft, Surface.bold, Mode.auto, Background.glow)
        val css = ThemeCss.compose("/* base */", tokens, Map("--btn" -> Pair("#112233", "#ddeeff")), ".x{top:0}")
        assertTrue(
          css.indexOf("--brand:#155e75") < css.indexOf("--btn:light-dark(#112233,#ddeeff)"),
          css.indexOf("--btn:light-dark") < css.indexOf(".x{top:0}"),
        )
      },
    ),
    suite("JSON")(
      test("tokens carry a single value as a string and a pair as an object, and read both back") {
        val tokens =
          ThemeTokens(base = "studio", overrides = None, variables = Some(Map("--rs" -> Single("8px"), "--btn" -> Pair("#112233", "#ddeeff"))))
        val json = tokens.toJson
        assertTrue(
          json.contains("\"--rs\":\"8px\""),
          json.contains("\"--btn\":{\"light\":\"#112233\",\"dark\":\"#ddeeff\"}"),
          json.fromJson[ThemeTokens] == Right(tokens),
        )
      },
      test("a theme stored with plain strings still reads, and one saved without variables has none") {
        assertTrue(
          """{"base":"studio","variables":{"--rs":"8px"}}""".fromJson[ThemeTokens].map(_.variables) == Right(Some(Map("--rs" -> Single("8px")))),
          """{"base":"studio","overrides":{}}""".fromJson[ThemeTokens].map(_.variables) == Right(None),
        )
      },
      test("refuses a malformed pair or a value that is neither") {
        assertTrue(
          """{"base":"studio","variables":{"--btn":{"light":"#111111"}}}""".fromJson[ThemeTokens].isLeft,
          """{"base":"studio","variables":{"--btn":{"light":"#111111","dark":"#222222","x":"y"}}}""".fromJson[ThemeTokens].isLeft,
          """{"base":"studio","variables":{"--btn":5}}""".fromJson[ThemeTokens].isLeft,
          """{"base":"studio","variables":{"--btn":["a"]}}""".fromJson[ThemeTokens].isLeft,
        )
      },
    ),
  )
