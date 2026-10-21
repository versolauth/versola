package versola.util.theme

import java.nio.charset.StandardCharsets

/** Turns theme tokens into the few CSS variables the login forms are built on (see `forms/common.css`).
  * Everything written is a fixed string or a validated [[BrandColor]], never free text.
  */
object ThemeCss:

  private val BaseStylesheetResource = "versola/forms/common.css"

  /** The foundation stylesheet every token theme is composed on (the login forms' `common.css`). It is a classpath resource
    * of this module, generated from `central-ui/forms/common.css` by `npm run build:forms`, and not the stored `default`
    * theme: that one is an ordinary token theme the operator edits. Read once; a build without it fails loudly rather
    * than serving unstyled pages.
    */
  lazy val baseStylesheet: String =
    val stream = Option(getClass.getClassLoader.getResourceAsStream(BaseStylesheetResource)).getOrElse:
      throw IllegalStateException(s"Classpath resource $BaseStylesheetResource is missing: run `npm run build:forms` in central-ui before building")
    try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
    finally stream.close()

  private val Ink = "#1c1b19"
  private val White = "#ffffff"

  // Radius of fields, buttons and the card.
  private def corners(shape: Shape): (String, String, String) = shape match
    case Shape.round => ("18px", "999px", "32px")
    case Shape.soft => ("14px", "14px", "24px")
    case Shape.sharp => ("4px", "4px", "6px")

  // WCAG relative luminance of an sRGB colour.
  private def luminance(red: Int, green: Int, blue: Int): Double =
    def channel(value: Int): Double =
      val unit = value / 255.0
      if unit <= 0.03928 then unit / 12.92 else math.pow((unit + 0.055) / 1.055, 2.4)
    0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)

  private def luminance(colour: BrandColor): Double =
    def part(from: Int) = Integer.parseInt(colour.value.substring(from, from + 2), 16)
    luminance(part(1), part(3), part(5))

  private def contrast(a: Double, b: Double): Double =
    (math.max(a, b) + 0.05) / (math.min(a, b) + 0.05)

  private val inkLuminance = luminance(0x1c, 0x1b, 0x19)

  /** White or near-black, whichever reads better on the colour (WCAG contrast). */
  def onBrand(colour: BrandColor): String =
    val own = luminance(colour)
    if contrast(own, 1.0) >= contrast(own, inkLuminance) then White else Ink

  // The card the brand colour is drawn on: white, or the dark card, which common.css makes by mixing 7% of the brand into
  // #1f1f20 (`--sheet`). The brand lightens it, so the check is made against the lightest card the brand can give.
  private val lightCard = 1.0

  private def srgbToLinear(value: Int): Double =
    val unit = value / 255.0
    if unit <= 0.04045 then unit / 12.92 else math.pow((unit + 0.055) / 1.055, 2.4)

  private def linearToSrgb(value: Double): Int =
    val clamped = math.max(0.0, math.min(1.0, value))
    val unit = if clamped <= 0.0031308 then clamped * 12.92 else 1.055 * math.pow(clamped, 1 / 2.4) - 0.055
    math.round(unit * 255).toInt

  private def toOklab(red: Int, green: Int, blue: Int): (Double, Double, Double) =
    val (r, g, b) = (srgbToLinear(red), srgbToLinear(green), srgbToLinear(blue))
    val l = math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    (
      0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
      1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
      0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )

  private def fromOklab(lightness: Double, a: Double, b: Double): (Int, Int, Int) =
    val l = math.pow(lightness + 0.3963377774 * a + 0.2158037573 * b, 3)
    val m = math.pow(lightness - 0.1055613458 * a - 0.0638541728 * b, 3)
    val s = math.pow(lightness - 0.0894841775 * a - 1.2914855480 * b, 3)
    (
      linearToSrgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
      linearToSrgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
      linearToSrgb(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
    )

  /** Luminance of the dark card for a brand: `color-mix(in oklab, brand 7%, #1f1f20)`, as common.css writes it. */
  private[theme] def darkCardLuminance(colour: BrandColor): Double =
    def part(from: Int) = Integer.parseInt(colour.value.substring(from, from + 2), 16)
    val (bl, ba, bb) = toOklab(part(1), part(3), part(5))
    val (gl, ga, gb) = toOklab(0x1f, 0x1f, 0x20)
    val (r, g, b) = fromOklab(bl * 0.07 + gl * 0.93, ba * 0.07 + ga * 0.93, bb * 0.07 + gb * 0.93)
    luminance(r, g, b)

  // WCAG asks 3:1 of a focus indicator and of a ticked box against what surrounds it.
  private val indicatorContrast = 3.0

  private def hex(red: Int, green: Int, blue: Int): String = f"#$red%02x$green%02x$blue%02x"

  /** The colour as it is, or moved towards black (on a light card) or white (on a dark one) until it can be seen
    * on the card. A pale brand such as yellow vanishes on white and a near-black one on the dark card, so the
    * rings and ticks use this, not the brand itself.
    */
  def indicator(colour: BrandColor, dark: Boolean): String =
    def part(from: Int) = Integer.parseInt(colour.value.substring(from, from + 2), 16)
    val (red, green, blue) = (part(1), part(3), part(5))
    val (towards, card) = if dark then (255, darkCardLuminance(colour)) else (0, lightCard)
    def mixed(step: Int) =
      def channel(value: Int) = (value + (towards - value) * step / 20.0).round.toInt
      (channel(red), channel(green), channel(blue))
    val enough = (0 to 20).iterator.map(mixed).find((r, g, b) => contrast(luminance(r, g, b), card) >= indicatorContrast)
    val (r, g, b) = enough.getOrElse(mixed(20))
    hex(r, g, b)

  private def onIndicator(indicatorColour: String): String =
    BrandColor.parse(indicatorColour).fold(_ => White, onBrand)

  private def focus(colour: BrandColor): String =
    val (light, dark) = (indicator(colour, dark = false), indicator(colour, dark = true))
    s"--focus:light-dark($light,$dark);--on-focus:light-dark(${onIndicator(light)},${onIndicator(dark)});"

  // The page colours and the halo of the glow, per scheme.
  private def surface(surface: Surface): String = surface match
    case Surface.bold => ""
    case Surface.soft =>
      "--page-light:color-mix(in oklab,var(--brand) 14%,#f7f4ee);" +
        "--page-dark:color-mix(in oklab,var(--brand) 7%,#0e0e0d);" +
        "--glow-light:color-mix(in oklab,var(--brand) 16%,transparent);" +
        "--glow-dark:color-mix(in oklab,var(--brand) 16%,transparent);"
    case Surface.neutral =>
      "--page-light:#ecebe7;--page-dark:#0b0b0a;--glow-light:transparent;--glow-dark:transparent;"

  private val SupportsLightDark = "@supports (color: light-dark(#000, #fff))"

  /** The share of the brand, in percent, mixed into the page at the strongest point of a gradient, in the light and in the
    * dark scheme. The page behind the card differs a lot (a pale one, a near-black one, the dark brand-tinted `bold` one),
    * and the brand text above the card (`--page-ink`) must stay readable on the strongest point, and the strongest point
    * must not shade into the colour of the dark card, so the strength follows the surface and is tested against every
    * built-in brand (ThemeBackgroundSpec). Glow and flat have no strength here: the
    * glow's lives in `--glow-light` and `--glow-dark` (see `surface`), flat has no tint.
    */
  private[theme] def tint(background: Background, surface: Surface): (Int, Int) = (background, surface) match
    case (Background.linear, Surface.bold) => (34, 45)
    case (Background.linear, Surface.soft) => (34, 34)
    case (Background.linear, Surface.neutral) => (14, 16)
    case (Background.horizon, Surface.bold) => (36, 46)
    case (Background.horizon, Surface.soft) => (36, 34)
    case (Background.horizon, Surface.neutral) => (18, 14)
    case (Background.glow | Background.flat, _) => (0, 0)

  // `--page-bg` is the one variable `body` paints (common.css). The default is the glow; a preset replaces only this
  // variable. The tint of linear and horizon is a colour written with light-dark(), so those two are returned apart,
  // for the @supports rule; a browser without light-dark() keeps the default.
  private def background(background: Background, surface: Surface): (String, String) =
    val (light, dark) = tint(background, surface)
    background match
      case Background.glow =>
        // The formula is common.css's; `--glow` is the halo of the scheme in force.
        ("--page-bg:radial-gradient(75% 70% at 50% 40%,var(--glow),transparent 75%),var(--page);", "")
      case Background.flat => ("--page-bg:var(--page);", "")
      case Background.linear =>
        (
          "",
          s"--page-tint:light-dark(color-mix(in oklab,var(--brand) $light%,var(--page-light)),color-mix(in oklab,var(--brand) $dark%,var(--page-dark)));" +
            "--page-bg:linear-gradient(160deg,var(--page-tint) 0%,var(--page) 65%);",
        )
      case Background.horizon =>
        (
          "",
          s"--page-tint:light-dark(color-mix(in oklab,var(--brand) $light%,transparent),color-mix(in oklab,var(--brand) $dark%,transparent));" +
            "--page-bg:radial-gradient(130% 60% at 50% 110%,var(--page-tint),transparent 72%),var(--page);",
        )

  // A fixed scheme also sets `--scheme-locked:1`, which the forms read to hide the visitor's Auto / Light / Dark toggle
  // (common.css documents it, default 0). `auto` writes nothing here: the toggle is shown.
  private def scheme(mode: Mode): String = mode match
    case Mode.auto => ""
    case Mode.light => "color-scheme:light;--scheme-locked:1;"
    case Mode.dark => "color-scheme:dark;--scheme-locked:1;"

  /** The block a theme adds after the default stylesheet. A declaration with light-dark() is dropped whole by a browser
    * that does not know it, so those sit in an `@supports` rule, as in common.css; there the default stylesheet's
    * values stay in force.
    */
  def generate(tokens: ResolvedTokens): String =
    val (field, button, card) = corners(tokens.shape)
    val page = surface(tokens.surface)
    // The glow is what common.css paints already, so it is left out here, as the bold page is.
    val (plainBackground, supportedBackground) = tokens.background match
      case Background.glow => ("", "")
      case other => background(other, tokens.surface)
    s"/* theme tokens: brand ${tokens.brand.value}, ${tokens.shape}, ${tokens.surface} page, ${tokens.background} background, ${tokens.mode} scheme */\n" +
      s":root{--brand:${tokens.brand.value};--on-brand:${onBrand(tokens.brand)};--rf:$field;--rb:$button;--rs:$card;$page$plainBackground${scheme(tokens.mode)}}\n" +
      s"$SupportsLightDark{:root{${focus(tokens.brand)}$supportedBackground}}"

  // Bold page variables as common.css defines them. `generate` leaves them out (the default stylesheet has them); a
  // partial block must write them, or an override of `bold` could not undo the soft or neutral surface under it.
  private val boldSurface =
    "--page-light:color-mix(in oklab,var(--brand) 9%,#f6f4ef);" +
      "--page-dark:color-mix(in oklab,var(--brand) 10%,#060707);" +
      "--glow-light:color-mix(in oklab,var(--brand) 30%,transparent);" +
      "--glow-dark:color-mix(in oklab,var(--brand) 55%,transparent);"

  /** The block a client adds on top of the theme it uses: only the variables its overrides change, empty when it
    * overrides nothing. A brand override needs no page variables: the soft and the bold page derive from
    * `var(--brand)`, so they follow it (as does the tint of a gradient). `base` is what the theme resolves to; it is named in the comment only,
    * because every override is written out even when it equals the base (the theme may be a stylesheet, whose
    * values are not known).
    */
  def generatePartial(base: ResolvedTokens, overrides: ThemeOverrides): String =
    if overrides.isEmpty then ""
    else
      val brand = overrides.brand.fold("")(colour => s"--brand:${colour.value};--on-brand:${onBrand(colour)};${focus(colour)}")
      val shape = overrides.shape.fold("") { value =>
        val (field, button, card) = corners(value)
        s"--rf:$field;--rb:$button;--rs:$card;"
      }
      val page = overrides.surface.fold("") {
        case Surface.bold => boldSurface
        case other => surface(other)
      }
      val mode = overrides.mode.fold("") {
        // An `auto` override must also undo a lock the theme set, so the partner can hand the choice to the visitor.
        case Mode.auto => "color-scheme:light dark;--scheme-locked:0;"
        case other => scheme(other)
      }
      // The strength of a tint follows the surface, so a surface override re-writes a background that is not the glow;
      // the glow follows `--glow` and needs nothing unless it is chosen. A brand override needs nothing: the tint is
      // mixed from `var(--brand)`.
      val effective = overrides.background.getOrElse(base.background)
      val rewritten = overrides.background.isDefined || (overrides.surface.isDefined && effective != Background.glow)
      val (plainBackground, supportedBackground) =
        if rewritten then background(effective, overrides.surface.getOrElse(base.surface)) else ("", "")
      val supported = Option.when(supportedBackground.nonEmpty)(s"\n$SupportsLightDark{:root{$supportedBackground}}").getOrElse("")
      s"/* client overrides on ${base.brand.value}, ${base.shape}, ${base.surface} page, ${base.background} background, ${base.mode} scheme */\n" +
        s":root{$brand$shape$page$plainBackground$mode}$supported"

  /** The stylesheet of a theme built from tokens: the current default, then what the five values set, then the
    * variables the theme sets one by one, then the theme's own CSS, which may be empty. Each later part wins over the
    * earlier ones where it sets the same thing.
    */
  def compose(defaultCss: String, tokens: ResolvedTokens, variables: Map[String, String], customCss: String): String =
    Seq(defaultCss, generate(tokens), ThemeVariables.render(variables), customCss).filter(_.nonEmpty).mkString("\n")
