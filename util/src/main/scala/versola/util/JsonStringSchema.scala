package versola.util

import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}
import zio.schema.Schema

/** The `Schema` of a value that travels as one JSON string (an enum of names), taken from its own codec, so that a
  * request parameter (`queryZIO`) is decoded exactly as the same value in a body is. A value that does not decode fails
  * the request as a bad one. A derived `Schema` of such an enum would not read a plain query parameter. */
object JsonStringSchema:
  def of[A](using codec: JsonCodec[A]): Schema[A] =
    Schema.primitive[String].transformOrFail(
      value => codec.decodeJson(JsonEncoder.string.encodeJson(value, None).toString),
      value => Right(codec.encodeJson(value, None).toString.stripPrefix("\"").stripSuffix("\"")),
    )
