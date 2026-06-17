package versola.central.configuration.forms

import zio.json.{JsonCodec, jsonDiscriminator}
import zio.schema.{Schema, derived}
import zio.schema.annotation.discriminatorName

@jsonDiscriminator("type")
@discriminatorName("type")
sealed trait BackendProperty derives Schema, JsonCodec:
  def name: String

case class BooleanProperty(name: String) extends BackendProperty derives Schema, JsonCodec
case class StringArrayProperty(name: String, allowedValues: Vector[String]) extends BackendProperty derives Schema, JsonCodec
case class NumberProperty(name: String, default: Int, min: Option[Int], max: Option[Int]) extends BackendProperty derives Schema, JsonCodec

case class FormRecord(
    id: FormId,
    version: Int,
    active: Boolean,
    style: String,
    jsSource: Option[String],
    jsCompiled: Option[String],
    localizations: Map[String, Map[String, String]],
    properties: Vector[BackendProperty],
) derives Schema, JsonCodec
