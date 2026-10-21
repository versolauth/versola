package versola.util.theme

/** The forms' theming API (`forms/common.css`): the CSS variables a theme may set directly, one by one, without
  * writing CSS. What is accepted is limited to this list and to values that cannot leave the declaration they are
  * written into, because they end up in a stylesheet served to every sign-in page.
  */
object ThemeVariables:

  /** The variables, in the order they are written, each with a one-line meaning. */
  val Allowed: Vector[(String, String)] = Vector(
    "--brand" -> "accent colour",
    "--on-brand" -> "text colour on the accent",
    "--focus" -> "focus rings and ticks",
    "--on-focus" -> "text colour on those",
    "--rf" -> "corner radius of fields",
    "--rb" -> "corner radius of buttons",
    "--rs" -> "corner radius of the card",
    "--page-light" -> "page colour, light scheme",
    "--page-dark" -> "page colour, dark scheme",
    "--glow-light" -> "glow behind the card, light scheme",
    "--glow-dark" -> "glow behind the card, dark scheme",
    "--control-h" -> "height of fields and buttons",
  )

  private val names: Set[String] = Allowed.map(_._1).toSet

  private val MaxLength = 120
  // Letters, digits and the punctuation of colours and lengths: `#fff`, `8px`, `rgb(0 0 0 / 50%)`, `color-mix(in oklab, red 20%, white)`.
  private val Safe = "^[A-Za-z0-9#%.,()\\- +_/]+$".r
  private val Forbidden = List("url(", "expression(", "/*", "*/", "@", "\\", ";", "}", "{", "<", "!")

  /** Why a value cannot be written, or `None` when it can. */
  def valueProblem(value: String): Option[String] =
    val text = value.trim
    val lower = text.toLowerCase
    if text.isEmpty then Some("is empty")
    else if text.length > MaxLength then Some(s"is longer than $MaxLength characters")
    else if Forbidden.exists(lower.contains) then Some("contains something a value may not: ; { } ! @ \\ url( expression( or a comment")
    else if !Safe.matches(text) then Some("contains a character a colour or a length does not use")
    else if text.count(_ == '(') != text.count(_ == ')') then Some("has unbalanced parentheses")
    else None

  /** The first reason the variables cannot be stored, naming the variable. */
  def validate(variables: Map[String, String]): Either[String, Unit] =
    val problems = Allowed.map(_._1).flatMap(name => variables.get(name).flatMap(value => valueProblem(value).map(problem => s"$name $problem")))
    variables.keys.toList.sorted.find(!names.contains(_)) match
      case Some(unknown) => Left(s"'$unknown' is not a variable a theme may set; allowed: ${Allowed.map(_._1).mkString(", ")}")
      case None => problems.headOption.toLeft(())

  /** The variables that can be written, in the order of [[Allowed]]; anything else is dropped (a stored theme is trusted
    * no further than its validation, so the page never carries a value this object would refuse).
    */
  def sanitized(variables: Map[String, String]): Vector[(String, String)] =
    Allowed.flatMap((name, _) => variables.get(name).filter(valueProblem(_).isEmpty).map(name -> _.trim))

  /** One `:root` block, empty when nothing is set. */
  def render(variables: Map[String, String]): String =
    val declarations = sanitized(variables)
    if declarations.isEmpty then ""
    else s"/* token overrides */\n:root{${declarations.map((name, value) => s"$name:$value;").mkString}}"
