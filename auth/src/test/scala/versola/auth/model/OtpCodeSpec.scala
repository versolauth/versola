package versola.auth.model

import zio.schema.Schema
import zio.schema.codec.JsonCodec as SchemaJsonCodec
import zio.test.*

object OtpCodeSpec extends ZIOSpecDefault:
  private val config = SchemaJsonCodec.Configuration.default

  private def decode(json: String) = SchemaJsonCodec.JsonDecoder.decode(Schema[OtpCode], json, config)

  def spec = suite("OtpCode")(
    test("decodes a numeric string via its schema") {
      assertTrue(decode("\"123456\"") == Right(OtpCode("123456")))
    },
    test("rejects an empty string via its schema") {
      assertTrue(decode("\"\"").isLeft)
    },
    test("rejects a non-digit string via its schema") {
      assertTrue(decode("\"12a456\"").isLeft)
    },
  )
