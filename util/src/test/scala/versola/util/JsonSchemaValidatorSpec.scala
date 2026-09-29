package versola.util

import zio.ZIO
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object JsonSchemaValidatorSpec extends ZIOSpecDefault:

  private def obj(json: String): Json.Obj =
    json.fromJson[Json.Obj].getOrElse(throw IllegalArgumentException(s"Invalid JSON: $json"))

  private def value(json: String): Json =
    json.fromJson[Json].getOrElse(throw IllegalArgumentException(s"Invalid JSON: $json"))

  /** Mirrors how an authorization detail type schema is composed: a shared RFC 9396 base
    * plus type-specific members, closed with `unevaluatedProperties`. */
  private val paymentSchema = obj("""
    {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "$defs": {
        "base": {
          "type": "object",
          "properties": {
            "type": { "type": "string" },
            "locations": { "type": "array", "items": { "type": "string" } },
            "actions": { "type": "array", "items": { "type": "string" } }
          },
          "required": ["type"]
        }
      },
      "allOf": [
        { "$ref": "#/$defs/base" },
        {
          "properties": {
            "instructedAmount": {
              "type": "object",
              "properties": { "currency": { "type": "string" }, "amount": { "type": "string" } },
              "required": ["currency", "amount"]
            }
          },
          "required": ["instructedAmount"]
        }
      ],
      "unevaluatedProperties": false
    }
  """)

  def spec = suite("JsonSchemaValidator")(
    test("accepts an instance matching the schema") {
      for
        validator <- ZIO.service[JsonSchemaValidator]
        errors <- validator.validate(
          paymentSchema,
          value("""{"type":"payment","actions":["initiate"],"instructedAmount":{"currency":"EUR","amount":"1.00"}}"""),
        )
      yield assertTrue(errors.isEmpty)
    },
    test("rejects an unknown member of a known type") {
      for
        validator <- ZIO.service[JsonSchemaValidator]
        errors <- validator.validate(
          paymentSchema,
          value("""{"type":"payment","instructedAmount":{"currency":"EUR","amount":"1.00"},"unknown":1}"""),
        )
      yield assertTrue(errors.nonEmpty)
    },
    test("rejects a missing required member") {
      for
        validator <- ZIO.service[JsonSchemaValidator]
        errors <- validator.validate(paymentSchema, value("""{"type":"payment"}"""))
      yield assertTrue(errors.nonEmpty)
    },
    test("rejects a member of the wrong type") {
      for
        validator <- ZIO.service[JsonSchemaValidator]
        errors <- validator.validate(
          paymentSchema,
          value("""{"type":"payment","actions":"initiate","instructedAmount":{"currency":"EUR","amount":"1.00"}}"""),
        )
      yield assertTrue(errors.nonEmpty)
    },
    test("accepts a well-formed schema") {
      for
        validator <- ZIO.service[JsonSchemaValidator]
        errors <- validator.validateSchema(paymentSchema)
      yield assertTrue(errors.isEmpty)
    },
    test("rejects a malformed schema") {
      for
        validator <- ZIO.service[JsonSchemaValidator]
        errors <- validator.validateSchema(obj("""{"type": 123}"""))
      yield assertTrue(errors.nonEmpty)
    },
    test("does not resolve a remote $ref") {
      for
        validator <- ZIO.service[JsonSchemaValidator]
        errors <- validator.validate(obj("""{"$ref":"https://example.invalid/schema.json"}"""), Json.Str("x"))
      yield assertTrue(errors.nonEmpty)
    },
    test("applies an edited schema rather than the cached previous version") {
      val before = obj("""{"type":"object","properties":{"a":{"type":"string"}},"required":["a"]}""")
      val after = obj("""{"type":"object","properties":{"a":{"type":"integer"}},"required":["a"]}""")
      val instance = value("""{"a":"text"}""")
      for
        validator <- ZIO.service[JsonSchemaValidator]
        accepted <- validator.validate(before, instance)
        rejected <- validator.validate(after, instance)
      yield assertTrue(accepted.isEmpty, rejected.nonEmpty)
    },
    test("warmCompile makes a later validate behave identically to one that compiled cold") {
      for
        validator <- ZIO.service[JsonSchemaValidator]
        _ <- validator.warmCompile(paymentSchema)
        valid <- validator.validate(
          paymentSchema,
          value("""{"type":"payment","instructedAmount":{"currency":"EUR","amount":"1.00"}}"""),
        )
        invalid <- validator.validate(paymentSchema, value("""{"type":"payment"}"""))
      yield assertTrue(valid.isEmpty, invalid.nonEmpty)
    },
    // The test above passes against a warmCompile that does nothing at all, because validate
    // compiles cold and answers the same either way. The cache is the only place the method has
    // an effect, so proving it did anything means looking there. Its own Impl, not the suite's
    // shared layer, so the counts are this test's alone.
    test("warmCompile compiles into the cache, and the later validate reuses it") {
      val validator = JsonSchemaValidator.Impl()
      for
        beforeWarm <- validator.compiledCount
        _ <- validator.warmCompile(paymentSchema)
        afterWarm <- validator.compiledCount
        _ <- validator.validate(paymentSchema, value("""{"type":"payment"}"""))
        afterValidate <- validator.compiledCount
      yield assertTrue(beforeWarm == 0, afterWarm == 1, afterValidate == 1)
    },
    test("warmCompile on a malformed schema does not fail; the error surfaces on validate instead") {
      for
        validator <- ZIO.service[JsonSchemaValidator]
        _ <- validator.warmCompile(obj("""{"$ref":"https://example.invalid/schema.json"}"""))
        errors <- validator.validate(obj("""{"$ref":"https://example.invalid/schema.json"}"""), Json.Str("x"))
      yield assertTrue(errors.nonEmpty)
    },
    // Same gap as above: that the failure is cached rather than recompiled on every validate is
    // the claim warmCompile's "one-time no-op" rests on, and only the cache shows it.
    test("warmCompile caches a malformed schema's failure instead of retrying the compile") {
      val malformed = obj("""{"$ref":"https://example.invalid/schema.json"}""")
      val validator = JsonSchemaValidator.Impl()
      for
        _ <- validator.warmCompile(malformed)
        afterWarm <- validator.compiledCount
        errors <- validator.validate(malformed, Json.Str("x"))
        afterValidate <- validator.compiledCount
      yield assertTrue(afterWarm == 1, errors.nonEmpty, afterValidate == 1)
    },
    test("keeps validating correctly once the compiled-schema cache has evicted entries") {
      // Comfortably exceeds the cache bound so the first schema is evicted before it is reused.
      val churn = 1200
      for
        validator <- ZIO.service[JsonSchemaValidator]
        _ <- ZIO.foreachDiscard(0 until churn): i =>
          validator.validate(obj(s"""{"type":"object","properties":{"p$i":{"type":"string"}}}"""), Json.Obj())
        errors <- validator.validate(paymentSchema, value("""{"type":"payment"}"""))
        valid <- validator.validate(
          paymentSchema,
          value("""{"type":"payment","instructedAmount":{"currency":"EUR","amount":"1.00"}}"""),
        )
      yield assertTrue(errors.nonEmpty, valid.isEmpty)
    },
  ).provide(JsonSchemaValidator.live)
