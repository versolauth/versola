package versola.util.theme

/** Turns theme tokens into the few CSS variables the login forms are built on (see `forms/common.css`).
  * Everything written is a fixed string or a validated [[BrandColor]], never free text.
  */
object ThemeCss:

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

  // The card the brand colour is drawn on: white, or the dark card's grey (a little lighter than it is, to stay on the safe side).
  private val lightCard = 1.0
  private val darkCard = luminance(0x26, 0x26, 0x28)
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
    val (towards, card) = if dark then (255, darkCard) else (0, lightCard)
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

  private def surface(surface: Surface): String = surface match
    case Surface.bold => ""
    case Surface.soft =>
      "--page-ink:light-dark(#1c1b19,#f3f2f0);" +
        "--page-light:color-mix(in oklab,var(--brand) 14%,#f7f4ee);" +
        "--page-dark:color-mix(in oklab,var(--brand) 7%,#0e0e0d);" +
        "--glow-dark:color-mix(in oklab,var(--brand) 16%,transparent);"
    case Surface.neutral =>
      "--page-ink:light-dark(#1c1b19,#f3f2f0);--page-light:#ecebe7;--page-dark:#0b0b0a;--glow-dark:transparent;"

  private def scheme(mode: Mode): String = mode match
    case Mode.auto => ""
    case Mode.light => "color-scheme:light;"
    case Mode.dark => "color-scheme:dark;"

  /** The block a theme adds after the default stylesheet. */
  def generate(tokens: ResolvedTokens): String =
    val (field, button, card) = corners(tokens.shape)
    s"/* theme tokens: brand ${tokens.brand.value}, ${tokens.shape}, ${tokens.surface} page, ${tokens.mode} scheme */\n" +
      s":root{--brand:${tokens.brand.value};--on-brand:${onBrand(tokens.brand)};${focus(tokens.brand)}--rf:$field;--rb:$button;--rs:$card;" +
      s"${surface(tokens.surface)}${scheme(tokens.mode)}}"

  /** The stylesheet of a theme built from tokens: the current default, then what the tokens set, then the
    * theme's own CSS, which may be empty.
    */
  def compose(defaultCss: String, tokens: ResolvedTokens, customCss: String): String =
    Seq(defaultCss, generate(tokens), customCss).filter(_.nonEmpty).mkString("\n")
