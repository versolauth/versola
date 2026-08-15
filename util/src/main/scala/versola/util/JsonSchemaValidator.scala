package versola.util

import com.networknt.schema.{InputFormat, Schema, SchemaLocation, SchemaRegistry, SpecificationVersion}
import zio.json.ast.Json
import zio.json.EncoderOps
import zio.{UIO, ULayer, ZIO, ZLayer}

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.ListHasAsScala

/** Validates JSON documents against JSON Schema 2020-12 schemas.
  *
  * The schemas are supplied by tenant administrators (RFC 9396 authorization detail type
  * definitions), so the underlying loader is restricted to the bundled JSON Schema
  * meta-schemas: a `$ref` to any other IRI is rejected rather than fetched, which would
  * otherwise make an admin-supplied schema an SSRF vector. Remote fetching is off by
  * default in the library; the allow-predicate additionally blocks arbitrary classpath
  * resources.
  *
  * Compiling a schema is expensive relative to validating against it, so compiled schemas
  * are cached by their canonical JSON text. Since editing a type's schema yields a new key
  * rather than replacing the old one, the cache is bounded: admin edits would otherwise
  * retain every superseded schema indefinitely. Lookups stay lock-free, and the bound is
  * enforced only when a not-yet-seen schema is compiled.
  */
trait JsonSchemaValidator:
  /** Returns the validation errors of `instance` against `schema`, empty when valid. */
  def validate(schema: Json.Obj, instance: Json): UIO[List[String]]

  /** Returns the errors of `schema` against the 2020-12 meta-schema, empty when `schema`
    * is a well-formed schema. */
  def validateSchema(schema: Json.Obj): UIO[List[String]]

object JsonSchemaValidator:
  val Dialect: String = SpecificationVersion.DRAFT_2020_12.getDialectId

  /** Upper bound on cached compiled schemas, across all tenants and detail types. */
  private val MaxCompiledSchemas = 1024

  private val MetaSchemaPrefix = "https://json-schema.org/draft/2020-12/"

  private val registry: SchemaRegistry =
    SchemaRegistry.withDefaultDialectId(
      Dialect,
      builder => builder.schemaLoader(loader => loader.allow(iri => iri.toString.startsWith(MetaSchemaPrefix))),
    )

  private val metaSchema: Schema =
    registry.getSchema(SchemaLocation.of(Dialect))

  val live: ULayer[JsonSchemaValidator] = ZLayer.succeed(Impl())

  class Impl extends JsonSchemaValidator:
    private val compiled = ConcurrentHashMap[String, Either[String, Schema]]()

    override def validate(schema: Json.Obj, instance: Json): UIO[List[String]] =
      compile(schema).flatMap:
        case Left(error) => ZIO.succeed(List(error))
        case Right(schema) => errorsOf(schema, instance)

    override def validateSchema(schema: Json.Obj): UIO[List[String]] =
      errorsOf(metaSchema, schema)

    private def errorsOf(schema: Schema, instance: Json): UIO[List[String]] =
      ZIO.attempt(schema.validate(instance.toJson, InputFormat.JSON).nn.asScala.toList.map(_.nn.getMessage.nn))
        .catchAll(error => ZIO.succeed(List(Option(error.getMessage).getOrElse("Schema validation failed"))))

    private def compile(schema: Json.Obj): UIO[Either[String, Schema]] =
      val key = schema.toJson
      ZIO.succeed:
        val hit = compiled.get(key)
        if hit != null then hit
        else
          // Schemas are only added when an admin defines or edits a type, so paying for the
          // bound here keeps it off the hot path. Dropping an arbitrary entry is enough: a
          // discarded schema is simply recompiled the next time it is used.
          if compiled.size() >= MaxCompiledSchemas then
            val stale = compiled.keys().nn.nextElement()
            compiled.remove(stale)
          compiled.computeIfAbsent(
            key,
            _ =>
              try Right(registry.getSchema(key, InputFormat.JSON).nn)
              catch case error: Exception => Left(Option(error.getMessage).getOrElse("Invalid JSON schema")),
          ).nn
