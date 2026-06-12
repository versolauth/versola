package versola.central.configuration.forms

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class GetAllFormsResponse(
    forms: Vector[FormRecord],
) derives Schema, JsonCodec

case class UpdateFormRequest(
    id: FormId,
    style: String,
    jsSource: Option[String],
    jsCompiled: Option[String],
    localizations: Map[String, Map[String, String]],
    properties: Vector[BackendProperty],
) derives Schema, JsonCodec

case class SetActiveVersionRequest(
    id: FormId,
    version: Int,
) derives Schema, JsonCodec
