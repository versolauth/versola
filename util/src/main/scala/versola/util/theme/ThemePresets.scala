package versola.util.theme

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

/** A built-in starting point for a theme. */
case class ThemePreset(
    id: String,
    title: Map[String, String],
    tokens: ResolvedTokens,
) derives JsonCodec, Schema

/** The themes that ship with Versola, a theme's possible bases. They are code, not rows: they cannot be edited
  * or deleted, and a change here reaches every theme built on one.
  */
object ThemePresets:
  val DefaultId = "versola"

  // The colours are literals; a test pins the list, so one that does not parse cannot drop a preset unnoticed.
  private def preset(id: String, en: String, ru: String, brand: String, shape: Shape, surface: Surface): Option[ThemePreset] =
    BrandColor.parse(brand).toOption.map: colour =>
      ThemePreset(id, Map("en" -> en, "ru" -> ru), ResolvedTokens(colour, shape, surface, Mode.auto))

  val all: Vector[ThemePreset] = Vector(
    preset(DefaultId, "Versola", "Versola", "#155e75", Shape.soft, Surface.bold),
    preset("studio", "Studio", "Студия", "#ff6b4a", Shape.round, Surface.bold),
    preset("fintech", "Fintech", "Финтех", "#5b5bd6", Shape.soft, Surface.neutral),
    preset("wellness", "Wellness", "Велнес", "#0e9f8e", Shape.round, Surface.soft),
    preset("editorial", "Editorial", "Редакционный", "#14130f", Shape.sharp, Surface.neutral),
    preset("sunny", "Sunny", "Солнечный", "#f5c518", Shape.soft, Surface.bold),
    preset("berry", "Berry", "Ягодный", "#b4348a", Shape.round, Surface.bold),
  ).flatten

  def find(id: String): Option[ThemePreset] = all.find(_.id == id)

  /** The tokens with the base's values under the overrides; fails when the base is not a preset. */
  def resolve(tokens: ThemeTokens): Either[String, ResolvedTokens] =
    find(tokens.base)
      .toRight(s"The base is not a built-in theme (${all.map(_.id).mkString(", ")})")
      .map: base =>
        ResolvedTokens(
          brand = tokens.overrides.brand.getOrElse(base.tokens.brand),
          shape = tokens.overrides.shape.getOrElse(base.tokens.shape),
          surface = tokens.overrides.surface.getOrElse(base.tokens.surface),
          mode = tokens.overrides.mode.getOrElse(base.tokens.mode),
        )
