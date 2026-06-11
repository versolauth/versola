package versola.oauth.client.model

import zio.json.{JsonCodec, jsonDiscriminator}

@jsonDiscriminator("type")
sealed trait BackendProperty derives JsonCodec:
  def name: String

case class BooleanProperty(name: String) extends BackendProperty derives JsonCodec
case class StringArrayProperty(name: String, allowedValues: Vector[String]) extends BackendProperty derives JsonCodec

case class FormRecord(
    id: String,
    version: Int,
    active: Boolean,
    style: String,
    jsSource: Option[String],
    jsCompiled: Option[String],
    localizations: Map[String, Map[String, String]],
    properties: Vector[BackendProperty],
) derives JsonCodec
