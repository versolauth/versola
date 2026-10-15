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

/** The colour scheme: follow the visitor's device, or always light or dark. */
enum Mode derives JsonCodec, Schema:
  case auto, light, dark

/** What a theme sets itself on top of its base preset; an absent value is the base's. */
case class ThemeOverrides(
    brand: Option[BrandColor] = None,
    shape: Option[Shape] = None,
    surface: Option[Surface] = None,
    mode: Option[Mode] = None,
) derives JsonCodec, Schema:
  def isEmpty: Boolean = brand.isEmpty && shape.isEmpty && surface.isEmpty && mode.isEmpty

/** A theme built from tokens: the id of a built-in preset and the values it overrides. */
case class ThemeTokens(
    base: String = ThemePresets.DefaultId,
    overrides: ThemeOverrides = ThemeOverrides(),
) derives JsonCodec, Schema

/** Every token with a value: a preset's, or a preset's with a theme's overrides laid over it. */
case class ResolvedTokens(
    brand: BrandColor,
    shape: Shape,
    surface: Surface,
    mode: Mode,
) derives JsonCodec, Schema
