package versola.central.configuration.jwks

import versola.util.{JWT, Secret}
import zio.json.ast.Json
import zio.test.*

object JwksRecordSpec extends ZIOSpecDefault:

  private def record(kid: String, algorithm: JWT.Algorithm, signable: Boolean = true) =
    JwksRecord(
      kid = kid,
      jwk = Json.Obj("alg" -> Json.Str(algorithm.toString), "kid" -> Json.Str(kid)),
      privateKey = Option.when(signable)(Secret(Array[Byte](1))),
    )

  def spec = suite("JwksRecord.preferredSigningKey")(
    test("prefers ES256 over PS256 when both can sign"):
      val keys = Vector(record("a-ps", JWT.Algorithm.PS256), record("b-es", JWT.Algorithm.ES256))
      assertTrue(JwksRecord.preferredSigningKey(keys).map(_.kid).contains("b-es"))
    ,
    test("falls back to PS256 when no ES256 key can sign"):
      val keys = Vector(record("a-ps", JWT.Algorithm.PS256), record("b-es", JWT.Algorithm.ES256, signable = false))
      assertTrue(JwksRecord.preferredSigningKey(keys).map(_.kid).contains("a-ps"))
    ,
    test("never picks RS256 or a verify-only key"):
      val keys = Vector(record("a-rs", JWT.Algorithm.RS256), record("b-ps", JWT.Algorithm.PS256, signable = false))
      assertTrue(JwksRecord.preferredSigningKey(keys).isEmpty)
    ,
    test("takes the newest kid of the preferred algorithm"):
      val keys = Vector(record("2026-01-es", JWT.Algorithm.ES256), record("2026-09-es", JWT.Algorithm.ES256))
      assertTrue(JwksRecord.preferredSigningKey(keys).map(_.kid).contains("2026-09-es")),
  )
