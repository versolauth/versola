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
      s":root{--brand:${tokens.brand.value};--on-brand:${onBrand(tokens.brand)};--rf:$field;--rb:$button;--rs:$card;" +
      s"${surface(tokens.surface)}${scheme(tokens.mode)}}"

  /** The stylesheet of a theme built from tokens: the current default, then what the tokens set, then the
    * theme's own CSS, which may be empty.
    */
  def compose(defaultCss: String, tokens: ResolvedTokens, customCss: String): String =
    Seq(defaultCss, generate(tokens), customCss).filter(_.nonEmpty).mkString("\n")
