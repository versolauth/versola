package versola.util.theme

import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps, JsonCodec, JsonDecoder, JsonEncoder}
import zio.schema.{Schema, derived}

/** What a token's value is, which decides the rule a value must pass. */
enum VariableKind derives JsonCodec, Schema:
  /** `#rgb`, `#rrggbb`, `#rrggbbaa`, a colour name, or `rgb()`, `hsl()`, `oklch()`, `color-mix()` and the like. */
  case colour

  /** A number with `px`, `rem`, `em`, `vw`, `vh` or `%`, or a plain `0`. */
  case length

  /** `none`, or one or more `[inset] x y [blur [spread]] colour` layers. */
  case shadow

  /** A whole number from 100 to 900. */
  case weight

  /** A list of font families by name (system fonts; no web fonts), such as `Georgia, serif`. */
  case font

/** The value of one token: a single value for both colour schemes, or one value per scheme (only for a token that is a
  * pair). In JSON a single value is a string, a pair is `{"light": "...", "dark": "..."}`.
  */
enum VariableValue derives Schema:
  case Single(value: String)
  case Pair(light: String, dark: String)

object VariableValue:
  given JsonCodec[VariableValue] = JsonCodec(
    JsonEncoder[Json].contramap:
      case Single(value) => Json.Str(value)
      case Pair(light, dark) => Json.Obj("light" -> Json.Str(light), "dark" -> Json.Str(dark))
    ,
    JsonDecoder[Json].mapOrFail:
      case Json.Str(value) => Right(Single(value))
      case Json.Obj(fields) =>
        val named = fields.toMap
        (named.get("light"), named.get("dark")) match
          case (Some(Json.Str(light)), Some(Json.Str(dark))) if named.size == 2 => Right(Pair(light, dark))
          case _ => Left("a pair is written {\"light\": \"...\", \"dark\": \"...\"}")
      case _ => Left("a value is a string, or a {\"light\", \"dark\"} pair"),
  )

/** A group of tokens in the editor and the docs. */
case class VariableGroup(
    id: String,
    title: Map[String, String],
) derives JsonCodec, Schema

/** One token a theme may set: what it is, where the editor lists it, what it means (for the UI, in each language) and what
  * the forms paint when it is not set.
  * `default` is the declaration's value as `forms/common.css` writes it (a test keeps the two equal); `start` is a concrete
  * value for the editor to begin from, since a default such as `var(--ink)` is not a colour a picker can show.
  */
case class ThemeVariable(
    name: String,
    group: String,
    kind: VariableKind,
    pair: Boolean,
    description: Map[String, String],
    default: String,
    start: VariableValue,
) derives JsonCodec, Schema

/** The tokens a theme may set, with the groups they are listed in: what the console's editor and the documentation show. */
case class ThemeVariableCatalog(
    groups: Vector[VariableGroup],
    variables: Vector[ThemeVariable],
) derives JsonCodec, Schema

/** The forms' theming API (`forms/common.css`): the CSS variables a theme may set directly, one by one, without
  * writing CSS. What is accepted is limited to this list and to values of the token's kind that cannot leave the
  * declaration they are written into, because they end up in a stylesheet served to every sign-in page.
  * The list is defined here once: central serves it (`GET /configuration/themes/variables`), the console and the
  * documentation read it, and a test keeps it equal to the `:root` of `common.css`.
  */
object ThemeVariables:

  val Groups: Vector[VariableGroup] = Vector(
    VariableGroup(id = "page", title = Map("en" -> "Page", "ru" -> "Страница")),
    VariableGroup(id = "brand", title = Map("en" -> "Brand and logo", "ru" -> "Бренд и логотип")),
    VariableGroup(id = "card", title = Map("en" -> "Card", "ru" -> "Карточка")),
    VariableGroup(id = "text", title = Map("en" -> "Text", "ru" -> "Текст")),
    VariableGroup(id = "fields", title = Map("en" -> "Fields", "ru" -> "Поля")),
    VariableGroup(id = "buttons", title = Map("en" -> "Buttons", "ru" -> "Кнопки")),
    VariableGroup(id = "links", title = Map("en" -> "Links and focus", "ru" -> "Ссылки и фокус")),
    VariableGroup(id = "lines", title = Map("en" -> "Lines", "ru" -> "Линии")),
    VariableGroup(id = "status", title = Map("en" -> "Status and overlays", "ru" -> "Статус и затемнение")),
  )

  private def single(value: String): VariableValue = VariableValue.Single(value)
  private def pair(light: String, dark: String): VariableValue = VariableValue.Pair(light = light, dark = dark)

  // A colour that has one value (for a pair of schemes see `pairColour`).
  private def colour(name: String, group: String, en: String, ru: String, default: String, start: String): ThemeVariable =
    ThemeVariable(
      name = name,
      group = group,
      kind = VariableKind.colour,
      pair = false,
      description = Map("en" -> en, "ru" -> ru),
      default = default,
      start = single(start),
    )

  // A colour that is a light-dark() pair by default: it takes one value, or one per scheme.
  private def pairColour(name: String, group: String, en: String, ru: String, default: String, light: String, dark: String): ThemeVariable =
    ThemeVariable(
      name = name,
      group = group,
      kind = VariableKind.colour,
      pair = true,
      description = Map("en" -> en, "ru" -> ru),
      default = default,
      start = pair(light = light, dark = dark),
    )

  private def of(name: String, group: String, kind: VariableKind, en: String, ru: String, default: String): ThemeVariable =
    ThemeVariable(
      name = name,
      group = group,
      kind = kind,
      pair = false,
      description = Map("en" -> en, "ru" -> ru),
      default = default,
      start = single(default),
    )

  /** The tokens, in the order they are written and listed. */
  val All: Vector[ThemeVariable] = Vector(
    colour(
      "--page-light",
      "page",
      "Page colour in the light scheme",
      "Цвет страницы в светлой теме",
      "color-mix(in oklab, var(--brand) 9%, #f6f4ef)",
      "#f6f4ef",
    ),
    colour(
      "--page-dark",
      "page",
      "Page colour in the dark scheme",
      "Цвет страницы в тёмной теме",
      "color-mix(in oklab, var(--brand) 10%, #060707)",
      "#060707",
    ),
    colour(
      "--glow-light",
      "page",
      "Glow behind the card in the light scheme",
      "Свечение за карточкой в светлой теме",
      "color-mix(in oklab, var(--brand) 30%, transparent)",
      "rgba(21, 94, 117, 0.3)",
    ),
    colour(
      "--glow-dark",
      "page",
      "Glow behind the card in the dark scheme",
      "Свечение за карточкой в тёмной теме",
      "color-mix(in oklab, var(--brand) 55%, transparent)",
      "rgba(21, 94, 117, 0.55)",
    ),
    colour(
      "--brand",
      "brand",
      "Accent colour; page tints and focus rings follow it",
      "Фирменный цвет; оттенки страницы и рамки фокуса следуют за ним",
      "#155e75",
      "#155e75",
    ),
    colour("--on-brand", "brand", "Text colour that reads on the accent colour", "Цвет текста, читаемый на фирменном цвете", "#ffffff", "#ffffff"),
    pairColour(
      "--page-ink",
      "brand",
      "Colour of the logo and wordmark above the card",
      "Цвет логотипа и названия над карточкой",
      "light-dark(#1c1b19, #f3f2f0)",
      "#1c1b19",
      "#f3f2f0",
    ),
    pairColour(
      "--logo-plate",
      "brand",
      "Plate behind a partner's logo in the app badge and on the consent screen, so a dark logo stays visible",
      "Подложка под логотипом приложения-партнёра на значке и на экране согласия, чтобы тёмный логотип был виден",
      "light-dark(var(--field), rgba(255, 255, 255, 0.94))",
      "#f5f3ee",
      "#f0f0f0",
    ),
    of("--logo-h", "brand", VariableKind.length, "Height of your logo above the card", "Высота вашего логотипа над карточкой", "40px"),
    pairColour(
      "--sheet",
      "card",
      "Surface of the card",
      "Фон карточки",
      "light-dark(#ffffff, color-mix(in oklab, var(--brand) 7%, #1f1f20))",
      "#ffffff",
      "#1e2326",
    ),
    pairColour(
      "--sheet-line",
      "card",
      "Border of the card (invisible in the light scheme by default)",
      "Рамка карточки (в светлой теме по умолчанию не видна)",
      "light-dark(transparent, rgba(255, 255, 255, 0.09))",
      "transparent",
      "#2f3031",
    ),
    of("--rs", "card", VariableKind.length, "Corner radius of the card", "Скругление углов карточки", "24px"),
    of(
      "--shadow",
      "card",
      VariableKind.shadow,
      "Shadow under the card; none removes it",
      "Тень под карточкой; none убирает её",
      "0 30px 60px -24px var(--shadow-color)",
    ),
    pairColour(
      "--shadow-color",
      "card",
      "Colour of the shadow under the card",
      "Цвет тени под карточкой",
      "light-dark(rgba(0, 0, 0, 0.45), rgba(0, 0, 0, 0.8))",
      "rgba(0, 0, 0, 0.45)",
      "rgba(0, 0, 0, 0.8)",
    ),
    of(
      "--card-w",
      "card",
      VariableKind.length,
      "Width of the card on a desktop; a phone uses the full width",
      "Ширина карточки на компьютере; на телефоне она во всю ширину",
      "460px",
    ),
    of(
      "--card-pad",
      "card",
      VariableKind.length,
      "Side padding inside the card (at most 22px on a phone)",
      "Боковые отступы внутри карточки (на телефоне не больше 22px)",
      "40px",
    ),
    pairColour(
      "--ink",
      "text",
      "Main text colour; links, the main button and the focused field's border follow it unless set",
      "Основной цвет текста; ссылки, главная кнопка и рамка поля в фокусе следуют за ним, пока их не задали",
      "light-dark(#1c1b19, #f3f2f0)",
      "#1c1b19",
      "#f3f2f0",
    ),
    pairColour(
      "--text-muted",
      "text",
      "Secondary text: descriptions, hints and labels",
      "Второстепенный текст: описания, подсказки и подписи",
      "light-dark(#6b6a66, #a9a9b0)",
      "#6b6a66",
      "#a9a9b0",
    ),
    pairColour(
      "--invert",
      "text",
      "Text colour on the --ink fill, the opposite of --ink",
      "Цвет текста на заливке цветом --ink, противоположный --ink",
      "light-dark(#ffffff, #1c1b19)",
      "#ffffff",
      "#1c1b19",
    ),
    of(
      "--heading-weight",
      "text",
      VariableKind.weight,
      "Weight of the page title, 100 to 900",
      "Насыщенность заголовка страницы, от 100 до 900",
      "750",
    ),
    of(
      "--font",
      "text",
      VariableKind.font,
      "Font of all text: names of fonts installed on the device, no web fonts",
      "Шрифт всего текста: названия шрифтов, установленных на устройстве, без веб-шрифтов",
      "system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif",
    ),
    pairColour(
      "--field",
      "fields",
      "Fill of fields, code boxes and quiet rows",
      "Заливка полей, ячеек кода и спокойных строк",
      "light-dark(#f5f3ee, color-mix(in oklab, var(--brand) 9%, #2b2b2d))",
      "#f5f3ee",
      "#2a2e30",
    ),
    pairColour(
      "--field-hover",
      "fields",
      "Fill of a field under the pointer",
      "Заливка поля под курсором",
      "light-dark(#edeae3, color-mix(in oklab, var(--brand) 12%, #323235))",
      "#edeae3",
      "#31373a",
    ),
    pairColour(
      "--field-focus",
      "fields",
      "Border of the focused field and of the active code box",
      "Рамка поля в фокусе и активной ячейки кода",
      "var(--ink)",
      "#1c1b19",
      "#f3f2f0",
    ),
    pairColour(
      "--placeholder",
      "fields",
      "Placeholder text in empty fields",
      "Подсказка в пустых полях",
      "color-mix(in srgb, var(--text-muted) 70%, transparent)",
      "rgba(107, 106, 102, 0.7)",
      "rgba(169, 169, 176, 0.7)",
    ),
    of("--rf", "fields", VariableKind.length, "Corner radius of fields", "Скругление углов полей", "14px"),
    of("--control-h", "fields", VariableKind.length, "Height of fields and buttons", "Высота полей и кнопок", "54px"),
    pairColour("--btn", "buttons", "Fill of the main button", "Заливка главной кнопки", "var(--ink)", "#1c1b19", "#f3f2f0"),
    pairColour("--on-btn", "buttons", "Text colour on the main button", "Цвет текста на главной кнопке", "var(--invert)", "#ffffff", "#1c1b19"),
    pairColour(
      "--btn-hover",
      "buttons",
      "Fill of the main button under the pointer",
      "Заливка главной кнопки под курсором",
      "color-mix(in srgb, var(--btn) 88%, var(--sheet))",
      "#373635",
      "#dbdbd8",
    ),
    pairColour(
      "--btn-secondary-line",
      "buttons",
      "Border of the secondary button",
      "Рамка второстепенной кнопки",
      "var(--line)",
      "#e6e2d8",
      "#343c41",
    ),
    pairColour(
      "--btn-secondary-ink",
      "buttons",
      "Text of the secondary button, and its border under the pointer",
      "Текст второстепенной кнопки и её рамка под курсором",
      "var(--ink)",
      "#1c1b19",
      "#f3f2f0",
    ),
    of("--rb", "buttons", VariableKind.length, "Corner radius of buttons", "Скругление углов кнопок", "14px"),
    pairColour("--link", "links", "Colour of links", "Цвет ссылок", "var(--ink)", "#1c1b19", "#f3f2f0"),
    pairColour(
      "--focus",
      "links",
      "Focus rings, ticks and the underline of links; keep it visible on the card",
      "Рамки фокуса, галочки и подчёркивание ссылок; оставьте его заметным на карточке",
      "light-dark(var(--brand), color-mix(in oklab, var(--brand) 50%, white))",
      "#155e75",
      "#8aaeba",
    ),
    pairColour(
      "--on-focus",
      "links",
      "Text colour on the focus colour, such as the tick in a checked box",
      "Цвет текста на цвете фокуса, например галочки в отмеченном поле",
      "light-dark(var(--on-brand), #1c1b19)",
      "#ffffff",
      "#1c1b19",
    ),
    pairColour(
      "--line",
      "lines",
      "Dividers, borders of rows, and the secondary button's border",
      "Разделители, рамки строк и рамка второстепенной кнопки",
      "light-dark(#e6e2d8, color-mix(in oklab, var(--brand) 10%, #38383b))",
      "#e6e2d8",
      "#343c41",
    ),
    pairColour(
      "--line-strong",
      "lines",
      "Stronger lines: the border of a tick box and the divider in the country selector",
      "Заметные линии: рамка переключателя и разделитель в выборе страны",
      "light-dark(#cfc9bb, #55535a)",
      "#cfc9bb",
      "#55535a",
    ),
    pairColour(
      "--danger",
      "status",
      "Colour of error text and of an invalid field's border",
      "Цвет текста ошибки и рамки поля с ошибкой",
      "light-dark(#c0341d, #ff8a73)",
      "#c0341d",
      "#ff8a73",
    ),
    colour(
      "--scrim",
      "status",
      "Dim layer behind dialogs and the phone's country sheet",
      "Затемнение за диалогами и списком стран на телефоне",
      "rgba(16, 24, 40, 0.45)",
      "rgba(16, 24, 40, 0.45)",
    ),
  )

  val catalog: ThemeVariableCatalog = ThemeVariableCatalog(groups = Groups, variables = All)

  private val byName: Map[String, ThemeVariable] = All.map(variable => variable.name -> variable).toMap

  def find(name: String): Option[ThemeVariable] = byName.get(name)

  private val MaxLength = 120
  // Letters, digits and the punctuation of colours and lengths: `#fff`, `8px`, `rgb(0 0 0 / 50%)`, `color-mix(in oklab, red 20%, white)`.
  private val Safe = "^[A-Za-z0-9#%.,()\\- +_/]+$".r
  private val Forbidden = List("url(", "expression(", "/*", "*/", "@", "\\", ";", "}", "{", "<", "!")

  /** Why a text cannot be written into a declaration at all, or `None` (the rule every kind starts from). */
  private[theme] def textProblem(value: String): Option[String] =
    val text = value.trim
    val lower = text.toLowerCase
    if text.isEmpty then Some("is empty")
    else if text.length > MaxLength then Some(s"is longer than $MaxLength characters")
    else if Forbidden.exists(lower.contains) then Some("contains something a value may not: ; { } ! @ \\ url( expression( or a comment")
    else if !Safe.matches(text) then Some("contains a character a value may not have: use letters, digits, spaces and # % . , ( ) - + _ /")
    else if text.count(_ == '(') != text.count(_ == ')') then Some("has unbalanced parentheses")
    else None

  private val ColourFunctions = Set("rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklab", "oklch", "color-mix", "color", "var")
  private val Hex = "^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$".r
  private val Word = "^[A-Za-z]+$".r
  private val FunctionCall = "^([a-z-]+)\\(.*\\)$".r
  private val FunctionName = "([A-Za-z-]+)\\(".r
  private val VarCall = "var\\(\\s*--[a-z0-9-]+\\s*\\)".r
  private val Length = "^(?:0|\\d+(?:\\.\\d+)?(?:px|rem|em|vw|vh|%))$".r
  private val Number = "^-?\\d+(?:\\.\\d+)?(?:px|rem|em)?$|^0$".r
  private val FontList = "^[A-Za-z-][A-Za-z0-9 \\-]*(?:, ?[A-Za-z-][A-Za-z0-9 \\-]*)*$".r

  private def colourProblem(text: String): Option[String] =
    val lower = text.toLowerCase
    if Hex.matches(text) || Word.matches(text) then None
    else
      lower match
        case FunctionCall(name) if ColourFunctions.contains(name) =>
          val calls = FunctionName.findAllMatchIn(lower).map(_.group(1)).toList
          val vars = VarCall.findAllMatchIn(lower).size
          if calls.exists(!ColourFunctions.contains(_)) then Some("uses a function that is not a colour function")
          else if calls.count(_ == "var") != vars then Some("uses var() with something other than a --name")
          else None
        case _ => Some("is not a colour: use #rrggbb, a colour name, or rgb(), hsl(), oklch() or color-mix()")

  private def lengthProblem(text: String): Option[String] =
    if Length.matches(text) then None else Some("is not a length: a number with px, rem, em, vw, vh or %, or 0")

  private def weightProblem(text: String): Option[String] =
    text.toIntOption.filter(weight => weight >= 100 && weight <= 900 && text == weight.toString) match
      case Some(_) => None
      case None => Some("is not a weight: a whole number from 100 to 900")

  private def fontProblem(text: String): Option[String] =
    if FontList.matches(text) then None else Some("is not a list of font names separated by commas")

  // Splits at the top level only: a comma or a space inside parentheses belongs to the colour function.
  private def splitTop(text: String, separator: Char): List[String] =
    val parts = List.newBuilder[String]
    val current = new StringBuilder
    var depth = 0
    text.foreach: char =>
      if char == '(' then depth += 1
      if char == ')' then depth -= 1
      if char == separator && depth == 0 then
        parts += current.toString
        current.clear()
      else current += char
    parts += current.toString
    parts.result().map(_.trim).filter(_.nonEmpty)

  private def shadowProblem(text: String): Option[String] =
    if text == "none" then None
    else
      val layers = splitTop(text, ',')
      val problems = layers.flatMap: layer =>
        val words = splitTop(layer, ' ')
        val inset = words.count(_ == "inset")
        val rest = words.filterNot(_ == "inset")
        val lengths = rest.takeWhile(word => Number.matches(word))
        val colours = rest.drop(lengths.size)
        if inset > 1 || lengths.size < 2 || lengths.size > 4 || colours.size != 1 then
          Some("has a shadow layer that is not [inset] x y [blur [spread]] colour")
        else colourProblem(colours.head).map(problem => s"has a shadow colour that $problem")
      if layers.isEmpty || layers.size > 4 then Some("is not none, or one to four shadow layers")
      else problems.headOption

  /** Why a text is not a value of the kind, or `None` when it is. */
  def kindProblem(kind: VariableKind, value: String): Option[String] =
    textProblem(value).orElse:
      val text = value.trim
      kind match
        case VariableKind.colour => colourProblem(text)
        case VariableKind.length => lengthProblem(text)
        case VariableKind.shadow => shadowProblem(text)
        case VariableKind.weight => weightProblem(text)
        case VariableKind.font => fontProblem(text)

  /** Why a value cannot be written for the token, or `None` when it can. */
  def valueProblem(variable: ThemeVariable, value: VariableValue): Option[String] = value match
    case VariableValue.Single(text) => kindProblem(variable.kind, text)
    case VariableValue.Pair(light, dark) =>
      if !variable.pair then Some("takes one value for both schemes, not a light and a dark one")
      else
        kindProblem(variable.kind, light).map(problem => s"(light) $problem").orElse(kindProblem(variable.kind, dark).map(problem =>
          s"(dark) $problem",
        ))

  /** The first reason the variables cannot be stored, naming the variable. */
  def validate(variables: Map[String, VariableValue]): Either[String, Unit] =
    variables.keys.toList.sorted.find(!byName.contains(_)) match
      case Some(unknown) => Left(s"'$unknown' is not a variable a theme may set; allowed: ${All.map(_.name).mkString(", ")}")
      case None =>
        val problems = All.flatMap(variable =>
          variables.get(variable.name).flatMap(value => valueProblem(variable, value).map(problem => s"${variable.name} $problem")),
        )
        problems.headOption.toLeft(())

  /** The declarations to write, in the order of [[All]]: a pair as `light-dark(a,b)`, anything else as it is; what the token
    * would refuse is dropped (a stored theme is trusted no further than its validation, so the page never carries a
    * value this object would refuse).
    */
  def sanitized(variables: Map[String, VariableValue]): Vector[(String, String)] =
    All.flatMap: variable =>
      variables.get(variable.name).filter(valueProblem(variable, _).isEmpty).map:
        case VariableValue.Single(text) => variable.name -> text.trim
        case VariableValue.Pair(light, dark) => variable.name -> s"light-dark(${light.trim},${dark.trim})"

  /** One `:root` block, empty when nothing is set. */
  def render(variables: Map[String, VariableValue]): String =
    val declarations = sanitized(variables)
    if declarations.isEmpty then ""
    else s"/* token overrides */\n:root{${declarations.map((name, value) => s"$name:$value;").mkString}}"
