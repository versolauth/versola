package versola.util.theme

import zio.json.*
import zio.test.*

object ThemeCssSpec extends ZIOSpecDefault:
  private def colour(raw: String): BrandColor =
    BrandColor.parse(raw).getOrElse(throw new IllegalArgumentException(raw))

  private def resolved(raw: String, shape: Shape = Shape.soft, surface: Surface = Surface.bold, mode: Mode = Mode.auto) =
    ResolvedTokens(colour(raw), shape, surface, mode)

  def spec = suite("ThemeCss")(
    suite("BrandColor")(
      test("accepts #rrggbb in either case and keeps it lower case") {
        assertTrue(BrandColor.parse("#FF6B4A") == BrandColor.parse("#ff6b4a"), BrandColor.parse("#ff6b4a").map(_.value) == Right("#ff6b4a"))
      },
      test("refuses anything that is not a six-digit hex colour, so a value cannot open a declaration") {
        val refused =
          List("red", "#fff", "#12345", "#1234567", "ff6b4a", "#ff6b4a;}body{display:none", "url(https://x)", "#ff6b4a ", "", "var(--x)", "#gggggg")
        assertTrue(refused.forall(BrandColor.parse(_).isLeft))
      },
      test("is validated when read from JSON") {
        assertTrue("\"#ff6b4a\"".fromJson[BrandColor].isRight, "\"red\"".fromJson[BrandColor].isLeft)
      },
    ),
    suite("onBrand")(
      test("is white on a dark colour and near-black on a light one") {
        assertTrue(
          ThemeCss.onBrand(colour("#155e75")) == "#ffffff",
          ThemeCss.onBrand(colour("#5b5bd6")) == "#ffffff",
          ThemeCss.onBrand(colour("#f5c518")) == "#1c1b19",
          ThemeCss.onBrand(colour("#ff6b4a")) == "#1c1b19",
        )
      },
    ),
    suite("indicator")(
      test("keeps every built-in brand visible (3:1) on the light and on the dark card") {
        def lum(colour: String) =
          def part(from: Int) =
            val unit = Integer.parseInt(colour.substring(from, from + 2), 16) / 255.0
            if unit <= 0.03928 then unit / 12.92 else math.pow((unit + 0.055) / 1.055, 2.4)
          0.2126 * part(1) + 0.7152 * part(3) + 0.0722 * part(5)
        def ratio(a: Double, b: Double) = (math.max(a, b) + 0.05) / (math.min(a, b) + 0.05)
        val darkCard = lum("#1f1f20")
        val weak =
          for
            preset <- ThemePresets.all
            isDark <- List(false, true)
            shown = ThemeCss.indicator(preset.tokens.brand, isDark)
            if ratio(lum(shown), if isDark then darkCard else 1.0) < 3.0
          yield s"${preset.id}/${if isDark then "dark" else "light"}"
        assertTrue(weak.isEmpty)
      },
      test("leaves a brand that is already visible alone") {
        assertTrue(ThemeCss.indicator(colour("#155e75"), dark = false) == "#155e75")
      },
      test("darkens yellow on the light card and lightens near-black on the dark one") {
        assertTrue(ThemeCss.indicator(colour("#f5c518"), dark = false) != "#f5c518", ThemeCss.indicator(colour("#14130f"), dark = true) != "#14130f")
      },
      test("is written as --focus for both schemes") {
        assertTrue(ThemeCss.generate(resolved("#f5c518")).contains("--focus:light-dark("))
      },
    ),
    suite("generate")(
      test("writes the brand, the text on it and the corners") {
        val css = ThemeCss.generate(resolved("#ff6b4a", Shape.round))
        assertTrue(
          css.contains("--brand:#ff6b4a;"),
          css.contains("--on-brand:#1c1b19;"),
          css.contains("--rf:18px;--rb:999px;--rs:32px;"),
        )
      },
      test("a bold page and the device's scheme add nothing beyond the colours and corners") {
        val css = ThemeCss.generate(resolved("#155e75"))
        assertTrue(!css.contains("--page-"), !css.contains("color-scheme"), css.contains("--rf:14px;--rb:14px;--rs:24px;"))
      },
      test("a soft or neutral page sets the page colours and the ink of the brand above the card") {
        val soft = ThemeCss.generate(resolved("#f5c518", surface = Surface.soft))
        val neutral = ThemeCss.generate(resolved("#5b5bd6", surface = Surface.neutral))
        assertTrue(
          soft.contains("--page-light:color-mix(in oklab,var(--brand) 14%,#f7f4ee);"),
          soft.contains("--page-ink:light-dark(#1c1b19,#f3f2f0);"),
          neutral.contains("--page-light:#ecebe7;--page-dark:#0b0b0a;--glow-dark:transparent;"),
        )
      },
      test("a fixed scheme is written as color-scheme") {
        assertTrue(
          ThemeCss.generate(resolved("#155e75", mode = Mode.dark)).contains("color-scheme:dark;"),
          ThemeCss.generate(resolved("#155e75", mode = Mode.light)).contains("color-scheme:light;"),
        )
      },
      test("is one rule and one comment, with nothing but the tokens in it") {
        val css = ThemeCss.generate(resolved("#ff6b4a", Shape.sharp, Surface.neutral, Mode.dark))
        assertTrue(css.count(_ == '{') == 1, css.count(_ == '}') == 1, css.linesIterator.size == 2)
      },
    ),
    suite("compose")(
      test("puts the default first, then the tokens, then the theme's own CSS") {
        val css = ThemeCss.compose("DEFAULT", resolved("#155e75"), "CUSTOM")
        assertTrue(css.indexOf("DEFAULT") < css.indexOf("--brand"), css.indexOf("--brand") < css.indexOf("CUSTOM"))
      },
      test("leaves out an empty custom CSS") {
        assertTrue(!ThemeCss.compose("DEFAULT", resolved("#155e75"), "").endsWith("\n"))
      },
    ),
    suite("ThemePresets")(
      test("lists the built-in themes, each once, with the default among them") {
        assertTrue(
          ThemePresets.all.map(_.id) == Vector("versola", "studio", "fintech", "wellness", "editorial", "sunny", "berry"),
          ThemePresets.find(ThemePresets.DefaultId).isDefined,
          ThemePresets.all.forall(p => p.title.contains("en") && p.title.contains("ru")),
        )
      },
      test("the default preset is what common.css already looks like") {
        val css = ThemeCss.generate(ThemePresets.all.head.tokens)
        assertTrue(css.contains("--brand:#155e75;"), css.contains("--rf:14px;--rb:14px;--rs:24px;"), !css.contains("--page-"))
      },
      test("resolves a base with its overrides laid over it") {
        val tokens = ThemeTokens("studio", ThemeOverrides(brand = BrandColor.parse("#112233").toOption, mode = Some(Mode.dark)))
        assertTrue(
          ThemePresets.resolve(tokens) == Right(ResolvedTokens(colour("#112233"), Shape.round, Surface.bold, Mode.dark)),
          ThemePresets.resolve(ThemeTokens("studio")).map(_.brand) == Right(colour("#ff6b4a")),
        )
      },
      test("refuses a base that is not built in") {
        assertTrue(ThemePresets.resolve(ThemeTokens("nope")).isLeft)
      },
    ),
    suite("JSON")(
      test("tokens round-trip, with absent overrides left out") {
        val tokens = ThemeTokens("fintech", ThemeOverrides(shape = Some(Shape.sharp)))
        assertTrue(tokens.toJson == """{"base":"fintech","overrides":{"shape":"sharp"}}""", tokens.toJson.fromJson[ThemeTokens] == Right(tokens))
      },
      test("a theme saved without overrides reads back with none") {
        assertTrue("""{"base":"studio"}""".fromJson[ThemeTokens] == Right(ThemeTokens("studio")))
      },
      test("refuses an unknown shape, surface or mode") {
        assertTrue(
          """{"base":"studio","overrides":{"shape":"oval"}}""".fromJson[ThemeTokens].isLeft,
          """{"base":"studio","overrides":{"surface":"glass"}}""".fromJson[ThemeTokens].isLeft,
          """{"base":"studio","overrides":{"mode":"sepia"}}""".fromJson[ThemeTokens].isLeft,
        )
      },
    ),
  )
