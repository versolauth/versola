package versola.central.configuration.forms

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

/** The texts of one form in one language: message key to text. Applied over the texts the form's own record carries
  * for that language, so a key set here wins and a key left out keeps the form's.
  */
case class FormTranslationRecord(
    formId: FormId,
    locale: String,
    texts: Map[String, String],
) derives Schema, JsonCodec
