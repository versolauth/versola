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
