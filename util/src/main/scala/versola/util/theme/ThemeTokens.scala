package versola.util.theme

import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}
import zio.schema.{Schema, derived}

/** A brand colour as `#rrggbb`, lower case. It is written into a stylesheet, so nothing else is accepted: a
  * colour name, a function or a stray `;` could close the declaration and inject rules.
  */
final case class BrandColor private (value: String)

object BrandColor:
  private val Pattern = "^#[0-9a-fA-F]{6}$".r

  def parse(raw: String): Either[String, BrandColor] =
    if Pattern.matches(raw) then Right(BrandColor(raw.toLowerCase))
    else Left(s"'$raw' is not a colour written as #rrggbb")

  given JsonCodec[BrandColor] = JsonCodec(JsonEncoder.string.contramap(_.value), JsonDecoder.string.mapOrFail(parse))
  given Schema[BrandColor] = Schema.primitive[String].transformOrFail(parse, colour => Right(colour.value))

/** How round the corners of fields, buttons and the card are. */
enum Shape derives JsonCodec, Schema:
  case round, soft, sharp

/** What fills the page behind the card: the brand colour, a pale tint of it, or a plain grey. */
enum Surface derives JsonCodec, Schema:
  case bold, soft, neutral

/** What lies behind the card, drawn over the page colour: the brand glowing from behind the card (the look
  * every theme had before this token), a soft diagonal wash, a light rising from the bottom edge, or the plain page
  * colour. Single-hue and restrained on purpose: each is a tint of the brand, never a second colour.
  */
enum Background derives JsonCodec, Schema:
  case glow, linear, horizon, flat

/** The colour scheme: follow the visitor's device, or always light or dark. */
enum Mode derives JsonCodec, Schema:
  case auto, light, dark

/** What a theme sets itself on top of its base preset; an absent value is the base's. */
case class ThemeOverrides(
    brand: Option[BrandColor],
    shape: Option[Shape],
    surface: Option[Surface],
    mode: Option[Mode],
    background: Option[Background],
) derives JsonCodec, Schema:
  def isEmpty: Boolean = brand.isEmpty && shape.isEmpty && surface.isEmpty && mode.isEmpty && background.isEmpty

object ThemeOverrides:
  val empty: ThemeOverrides = ThemeOverrides(None, None, None, None, None)

/** A theme built from tokens: the id of a built-in preset, the values it overrides and the forms' CSS variables it sets
  * directly (see [[ThemeVariables]]); absent overrides and variables are none.
  */
case class ThemeTokens(
    base: String,
    overrides: Option[ThemeOverrides],
    variables: Option[Map[String, VariableValue]],
) derives JsonCodec, Schema

/** Every token with a value: a preset's, or a preset's with a theme's overrides laid over it. */
case class ResolvedTokens(
    brand: BrandColor,
    shape: Shape,
    surface: Surface,
    mode: Mode,
    background: Background,
) derives JsonCodec, Schema
