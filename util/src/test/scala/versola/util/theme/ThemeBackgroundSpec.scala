package versola.util.theme

import zio.json.*
import zio.test.*

import scala.math.{cbrt, max, min, pow}

object ThemeBackgroundSpec extends ZIOSpecDefault:
  private type Rgb = (Double, Double, Double)

  private def rgb(hex: String): Rgb =
    def part(from: Int) = Integer.parseInt(hex.substring(from, from + 2), 16).toDouble
    (part(1), part(3), part(5))

  private def toLinear(value: Double): Double =
    val unit = value / 255.0
    if unit <= 0.04045 then unit / 12.92 else pow((unit + 0.055) / 1.055, 2.4)

  private def fromLinear(value: Double): Double =
    val clamped = max(0.0, min(1.0, value))
    255.0 * (if clamped <= 0.0031308 then clamped * 12.92 else 1.055 * pow(clamped, 1 / 2.4) - 0.055)

  private def toOklab(colour: Rgb): (Double, Double, Double) =
    val (r, g, b) = (toLinear(colour._1), toLinear(colour._2), toLinear(colour._3))
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    (
      0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
      1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
      0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )

  private def fromOklab(lab: (Double, Double, Double)): Rgb =
    val (lightness, a, b) = lab
    val l = pow(lightness + 0.3963377774 * a + 0.2158037573 * b, 3)
    val m = pow(lightness - 0.1055613458 * a - 0.0638541728 * b, 3)
    val s = pow(lightness - 0.0894841775 * a - 1.2914855480 * b, 3)
    (
      fromLinear(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
      fromLinear(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
      fromLinear(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
    )

  // `color-mix(in oklab, first share%, second)`.
  private def mix(first: Rgb, share: Double, second: Rgb): Rgb =
    val (x, y) = (toOklab(first), toOklab(second))
    fromOklab((x._1 * share + y._1 * (1 - share), x._2 * share + y._2 * (1 - share), x._3 * share + y._3 * (1 - share)))

  // A colour with an alpha laid over an opaque one: how `color-mix(brand X%, transparent)` reaches the screen.
  private def over(top: Rgb, alpha: Double, bottom: Rgb): Rgb =
    (top._1 * alpha + bottom._1 * (1 - alpha), top._2 * alpha + bottom._2 * (1 - alpha), top._3 * alpha + bottom._3 * (1 - alpha))

  private def luminance(colour: Rgb): Double =
    0.2126 * toLinear(colour._1) + 0.7152 * toLinear(colour._2) + 0.0722 * toLinear(colour._3)

  private def contrast(a: Rgb, b: Rgb): Double =
    val (x, y) = (luminance(a), luminance(b))
    (max(x, y) + 0.05) / (min(x, y) + 0.05)

  // The page colours and the ink above the card, per surface and scheme, as common.css and ThemeCss.surface write them
  // (a test below pins the common.css ones). The dark card is `color-mix(in oklab, brand 7%, #1f1f20)`.
  private final case class Page(colour: Rgb, ink: Rgb, card: Rgb)

  private def page(brand: Rgb, surface: Surface, dark: Boolean): Page =
    val card = if dark then mix(brand, 0.07, rgb("#1f1f20")) else rgb("#ffffff")
    (surface, dark) match
      case (Surface.bold, false) => Page(mix(brand, 0.22, rgb("#0c1110")), rgb("#ffffff"), card)
      case (Surface.bold, true) => Page(mix(brand, 0.10, rgb("#060707")), rgb("#f3f2f0"), card)
      case (Surface.soft, false) => Page(mix(brand, 0.14, rgb("#f7f4ee")), rgb("#1c1b19"), card)
      case (Surface.soft, true) => Page(mix(brand, 0.07, rgb("#0e0e0d")), rgb("#f3f2f0"), card)
      case (Surface.neutral, false) => Page(rgb("#ecebe7"), rgb("#1c1b19"), card)
      case (Surface.neutral, true) => Page(rgb("#0b0b0a"), rgb("#f3f2f0"), card)

  // The glow's strength in the dark scheme (`--glow-dark`, as common.css and ThemeCss.surface write it); transparent in the light one.
  private def glowShare(surface: Surface): Double = surface match
    case Surface.bold => 0.55
    case Surface.soft => 0.16
    case Surface.neutral => 0.0

  private val brands = ThemePresets.all.map(preset => preset.id -> rgb(preset.tokens.brand.value))
  private val cases =
    for
      (id, brand) <- brands
      surface <- Surface.values.toVector
      dark <- Vector(false, true)
    yield (s"$id/$surface/${if dark then "dark" else "light"}", brand, surface, dark)

  private def tintedPage(background: Background, brand: Rgb, surface: Surface, dark: Boolean): Rgb =
    val here = page(brand, surface, dark).colour
    val (light, deep) = ThemeCss.tint(background, surface)
    val share = (if dark then deep else light) / 100.0
    background match
      case Background.linear => mix(brand, share, here)   // the 0% stop: brand mixed into the page
      case Background.horizon => over(brand, share, here) // the strongest stop: brand at that alpha over the page
      case Background.glow | Background.flat => here

  private val gradients = Vector(Background.linear, Background.horizon)

  // The brand wordmark and the "return" link sit on the page above the card (`.page-brand`, `--page-ink`): text, 4.5:1.
  private val textContrast = 4.5
  // The card has a border and a shadow, so it needs no more than to be told apart from the page: a ratio a user with
  // ordinary vision sees on a large area. The page lightens towards the brand and the dark card is dark, so the
  // strongest point must stay clear of the card's own colour (the gradient crosses it somewhere in between, as the glow does).
  private val cardSeparation = 1.05

  def spec = suite("Background")(
    suite("the preset's CSS")(
      test("glow is what common.css paints already, so a theme on glow writes no page background") {
        val css = ThemeCss.generate(ResolvedTokens(ThemePresets.all.head.tokens.brand, Shape.soft, Surface.bold, Mode.auto, Background.glow))
        assertTrue(!css.contains("--page-bg"), !css.contains("--page-tint"))
      },
      test("flat paints the page colour and needs no light-dark()") {
        val css = ThemeCss.generate(ResolvedTokens(ThemePresets.all.head.tokens.brand, Shape.soft, Surface.bold, Mode.auto, Background.flat))
        val declared = css.indexOf("--page-bg:var(--page);")
        assertTrue(declared > 0, declared < css.indexOf("@supports"), !css.contains("--page-tint"))
      },
      test("linear and horizon tint the page with the brand, in the strength of the surface and scheme, inside @supports") {
        def generated(background: Background, surface: Surface) =
          ThemeCss.generate(ResolvedTokens(ThemePresets.all.head.tokens.brand, Shape.soft, surface, Mode.auto, background))
        val checks =
          for
            background <- gradients
            surface <- Surface.values.toVector
          yield
            val css = generated(background, surface)
            val (light, dark) = ThemeCss.tint(background, surface)
            val supported = css.indexOf("@supports (color: light-dark(#000, #fff)){")
            val base = if background == Background.linear then ("var(--page-light)", "var(--page-dark)") else ("transparent", "transparent")
            supported > 0 &&
            css.indexOf("--page-bg:") > supported &&
            css.contains(
              s"--page-tint:light-dark(color-mix(in oklab,var(--brand) $light%,${base._1}),color-mix(in oklab,var(--brand) $dark%,${base._2}));",
            )
        assertTrue(checks.forall(identity))
      },
      test("linear is a diagonal wash from the tint to the page, horizon a light rising from the bottom edge") {
        def css(background: Background) =
          ThemeCss.generate(ResolvedTokens(ThemePresets.all.head.tokens.brand, Shape.soft, Surface.soft, Mode.auto, background))
        assertTrue(
          css(Background.linear).contains("--page-bg:linear-gradient(160deg,var(--page-tint) 0%,var(--page) 65%);"),
          css(Background.horizon).contains("--page-bg:radial-gradient(130% 60% at 50% 110%,var(--page-tint),transparent 72%),var(--page);"),
          !css(Background.linear).contains("hue"),
        )
      },
      test("a client override of the background resets the preset explicitly, the glow included") {
        val base = ResolvedTokens(ThemePresets.all.head.tokens.brand, Shape.soft, Surface.soft, Mode.auto, Background.horizon)
        def partial(background: Background) = ThemeCss.generatePartial(base, ThemeOverrides(None, None, None, None, Some(background)))
        assertTrue(
          partial(Background.glow).contains("--page-bg:radial-gradient(75% 70% at 50% 40%,var(--glow),transparent 75%),var(--page);"),
          partial(Background.flat).contains("--page-bg:var(--page);"),
          partial(Background.linear).contains("@supports (color: light-dark(#000, #fff)){:root{--page-tint:"),
          partial(Background.horizon).contains("--page-bg:radial-gradient(130% 60% at 50% 110%"),
          // the light-dark() tint is only in the @supports rule, never in the plain one
          partial(Background.linear).indexOf("light-dark(") > partial(Background.linear).indexOf("@supports"),
        )
      },
      test("a surface override re-writes a gradient background, so its strength follows the new surface; the glow needs nothing") {
        def base(background: Background) = ResolvedTokens(ThemePresets.all.head.tokens.brand, Shape.soft, Surface.soft, Mode.auto, background)
        val onSurface = ThemeOverrides(None, None, Some(Surface.neutral), None, None)
        val (light, dark) = ThemeCss.tint(Background.linear, Surface.neutral)
        assertTrue(
          ThemeCss.generatePartial(
            base(Background.linear),
            onSurface,
          ).contains(s"var(--brand) $light%,var(--page-light)),color-mix(in oklab,var(--brand) $dark%"),
          !ThemeCss.generatePartial(base(Background.glow), onSurface).contains("--page-bg"),
        )
      },
      test("a brand override writes no background: the tint is mixed from var(--brand)") {
        val base = ResolvedTokens(ThemePresets.all.head.tokens.brand, Shape.soft, Surface.soft, Mode.auto, Background.linear)
        val css = ThemeCss.generatePartial(base, ThemeOverrides(BrandColor.parse("#112233").toOption, None, None, None, None))
        assertTrue(!css.contains("--page-bg"))
      },
    ),
    suite("the built-in presets")(
      test("every preset has a background; the default is the glow, so it stays what common.css paints") {
        assertTrue(
          ThemePresets.all.map(preset => preset.id -> preset.tokens.background) == Vector(
            "versola" -> Background.glow,
            "studio" -> Background.glow,
            "fintech" -> Background.linear,
            "wellness" -> Background.horizon,
            "editorial" -> Background.flat,
            "sunny" -> Background.glow,
            "berry" -> Background.glow,
          ),
        )
      },
      test("the page colours this spec checks are the ones common.css defines") {
        val css = ThemeCss.baseStylesheet
        assertTrue(
          css.contains("--page-light: color-mix(in oklab, var(--brand) 22%, #0c1110);"),
          css.contains("--page-dark: color-mix(in oklab, var(--brand) 10%, #060707);"),
          css.contains("--glow-dark: color-mix(in oklab, var(--brand) 55%, transparent);"),
          css.contains("--page-ink: light-dark(#ffffff, #f3f2f0);"),
          css.contains("--sheet: light-dark(#ffffff, color-mix(in oklab, var(--brand) 7%, #1f1f20));"),
          css.contains("--page-bg: radial-gradient(75% 70% at 50% 40%, var(--glow), transparent 75%), var(--page);"),
          css.contains("background: var(--page-bg);"),
        )
      },
      test("a theme is decoded with its background, and an unknown one is refused") {
        val tokens = ThemeTokens("fintech", Some(ThemeOverrides(None, None, None, None, Some(Background.horizon))))
        assertTrue(
          tokens.toJson == """{"base":"fintech","overrides":{"background":"horizon"}}""",
          tokens.toJson.fromJson[ThemeTokens] == Right(tokens),
          """{"base":"studio","overrides":{"background":"aurora"}}""".fromJson[ThemeTokens].isLeft,
        )
      },
    ),
    suite("contrast")(
      test("the text above the card keeps 4.5:1 on the strongest point of linear and horizon, for every built-in brand, surface and scheme") {
        val weak =
          for
            (label, brand, surface, dark) <- cases
            background <- gradients
            ratio = contrast(tintedPage(background, brand, surface, dark), page(brand, surface, dark).ink)
            if ratio < textContrast
          yield f"$background/$label $ratio%.2f"
        assertTrue(weak.isEmpty)
      },
      test("the text above the card keeps 4.5:1 on the glow where the wordmark sits (top centre, up to 30% of the height)") {
        // `radial-gradient(75% 70% at 50% 40%, glow, transparent 75%)`: alpha falls linearly from the centre to 75% of the radius.
        def glowAt(height: Double, brand: Rgb, surface: Surface, dark: Boolean): Rgb =
          val distance = math.abs(height - 0.40) / 0.70
          val alpha = if dark then glowShare(surface) * max(0.0, 1 - distance / 0.75) else 0.0
          over(brand, alpha, page(brand, surface, dark).colour)
        val weak =
          for
            (label, brand, surface, dark) <- cases
            height <- Vector(0.02, 0.10, 0.20, 0.30)
            ratio = contrast(glowAt(height, brand, surface, dark), page(brand, surface, dark).ink)
            if ratio < textContrast
          yield f"$label at $height%.2f $ratio%.2f"
        assertTrue(weak.isEmpty)
      },
      test("the card stays apart from the page: from the plain page and from the strongest point of linear and horizon") {
        val weak =
          for
            (label, brand, surface, dark) <- cases
            background <- gradients
            here = page(brand, surface, dark)
            ratio = min(contrast(here.card, here.colour), contrast(here.card, tintedPage(background, brand, surface, dark)))
            if ratio < cardSeparation
          yield f"$background/$label $ratio%.3f"
        assertTrue(weak.isEmpty)
      },
      test("a light page is separated from the white card by more than a hair") {
        val weak =
          for
            (label, brand, surface, dark) <- cases
            if !dark
            background <- gradients
            here = page(brand, surface, dark)
            ratio = contrast(here.card, tintedPage(background, brand, surface, dark))
            if ratio < 1.1
          yield f"$background/$label $ratio%.3f"
        assertTrue(weak.isEmpty)
      },
    ),
  )
