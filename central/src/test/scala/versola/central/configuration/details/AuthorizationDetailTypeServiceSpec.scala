package versola.central.configuration.details

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateAuthorizationDetailTypeRequest, UpdateAuthorizationDetailTypeRequest}
import versola.util.{JsonSchemaValidator, ReloadingCache}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.prelude.EqualOps
import zio.test.*

object AuthorizationDetailTypeServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenantId = TenantId("tenant-a")
  private val otherTenantId = TenantId("tenant-b")
  private val paymentType = AuthorizationDetailType("payment_initiation")
  private val accountType = AuthorizationDetailType("account_information")

  private def obj(json: String): Json.Obj = json.fromJson[Json.Obj].toOption.get

  private val schema = obj("""
    {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "properties": { "type": { "type": "string" } },
      "required": ["type"],
      "unevaluatedProperties": false
    }
  """)

  private val paymentRecord = AuthorizationDetailTypeRecord(
    tenantId = tenantId,
    `type` = paymentType,
    description = Map("en" -> "Payment initiation"),
    schema = schema,
  )

  private val otherTenantRecord = AuthorizationDetailTypeRecord(
    tenantId = otherTenantId,
    `type` = accountType,
    description = Map("en" -> "Account information"),
    schema = schema,
  )

  private val createRequest = CreateAuthorizationDetailTypeRequest(
    tenantId = tenantId,
    `type` = paymentType,
    description = Map("en" -> "Payment initiation"),
    schema = schema,
  )

  private val updateRequest = UpdateAuthorizationDetailTypeRequest(
    tenantId = tenantId,
    `type` = paymentType,
    description = Map("en" -> "Payment initiation v2"),
    schema = schema,
  )

  class Env(initial: Vector[AuthorizationDetailTypeRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[AuthorizationDetailTypeRepository]
    val service = AuthorizationDetailTypeService.Impl(cache, repository, JsonSchemaValidator.Impl())

  def spec = suite("AuthorizationDetailTypeService")(
    test("getTenantTypes filters cache by tenant") {
      val env = new Env(Vector(paymentRecord, otherTenantRecord))

      for
        result <- env.service.getTenantTypes(tenantId, offset = 0, limit = None)
      yield assertTrue(result == Vector(paymentRecord))
    },
    test("getTenantTypes applies pagination after filtering") {
      val env = new Env(Vector(paymentRecord, paymentRecord.copy(`type` = accountType), otherTenantRecord))

      for
        result <- env.service.getTenantTypes(tenantId, offset = 1, limit = Some(1))
      yield assertTrue(result == Vector(paymentRecord.copy(`type` = accountType)))
    },
    test("createType delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.createType.succeedsWith(())
        result <- env.service.createType(createRequest)
      yield assertTrue(
        result == Right(()),
        env.repository.createType.calls == List((tenantId, paymentType, createRequest.description, schema)),
      )
    },
    test("createType rejects a schema that is not a valid JSON Schema") {
      val env = new Env()

      for
        result <- env.service.createType(createRequest.copy(schema = obj("""{"type": 123}""")))
      yield assertTrue(
        result.left.exists {
          case AuthorizationDetailTypeValidationError.InvalidSchema(errors) => errors.nonEmpty
        },
        env.repository.createType.calls.isEmpty,
      )
    },
    test("updateType delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.updateType.succeedsWith(())
        result <- env.service.updateType(updateRequest)
      yield assertTrue(
        result == Right(()),
        env.repository.updateType.calls == List((tenantId, paymentType, updateRequest.description, schema)),
      )
    },
    test("updateType rejects a schema that is not a valid JSON Schema") {
      val env = new Env()

      for
        result <- env.service.updateType(updateRequest.copy(schema = obj("""{"required": "type"}""")))
      yield assertTrue(result.isLeft, env.repository.updateType.calls.isEmpty)
    },
    test("deleteType delegates tenant and type to repository") {
      val env = new Env()

      for
        _ <- env.repository.deleteType.succeedsWith(())
        _ <- env.service.deleteType(tenantId, paymentType)
      yield assertTrue(env.repository.deleteType.calls == List((tenantId, paymentType)))
    },
    test("sync removes cached type on delete event") {
      val env = new Env(Vector(paymentRecord, otherTenantRecord))

      for
        _ <- env.service.sync(SyncEvent.AuthorizationDetailTypesUpdated(tenantId, paymentType, SyncEvent.Op.DELETE))
        cached <- env.cache.get
      yield assertTrue(cached == Vector(otherTenantRecord))
    },
    test("sync upserts fetched type for non-delete event") {
      val env = new Env(Vector(paymentRecord, otherTenantRecord))
      val updated = paymentRecord.copy(description = Map("en" -> "Updated"))

      for
        _ <- env.repository.findType.succeedsWith(Some(updated))
        _ <- env.service.sync(SyncEvent.AuthorizationDetailTypesUpdated(tenantId, paymentType, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(cached.contains(updated))
    },
  )
