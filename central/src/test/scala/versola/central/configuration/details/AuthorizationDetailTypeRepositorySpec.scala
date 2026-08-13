package versola.central.configuration.details

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.json.*
import zio.json.ast.Json
import zio.test.*

trait AuthorizationDetailTypeRepositorySpec extends DatabaseSpecBase[AuthorizationDetailTypeRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val tenantId = TenantId("tenant-a")
  val paymentType = AuthorizationDetailType("payment_initiation")

  private def obj(json: String): Json.Obj = json.fromJson[Json.Obj].toOption.get

  val schema = obj("""
    {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "properties": { "type": { "type": "string" } },
      "required": ["type"],
      "unevaluatedProperties": false
    }
  """)

  override def testCases(env: AuthorizationDetailTypeRepositorySpec.Env) =
    List(
      test("create type and persist its schema verbatim") {
        for
          _ <- env.repository.createType(tenantId, paymentType, Map("en" -> "Payment initiation"), schema)
          found <- env.repository.findType(tenantId, paymentType)
        yield assertTrue(
          found == Some(
            AuthorizationDetailTypeRecord(
              tenantId = tenantId,
              `type` = paymentType,
              description = Map("en" -> "Payment initiation"),
              schema = schema,
            )
          )
        )
      },
      test("update type replaces description and schema") {
        val newSchema = obj("""{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object"}""")

        for
          _ <- env.repository.createType(tenantId, paymentType, Map("en" -> "Payment initiation"), schema)
          _ <- env.repository.updateType(tenantId, paymentType, Map("en" -> "Payments"), newSchema)
          found <- env.repository.findType(tenantId, paymentType)
        yield assertTrue(
          found.map(_.description) == Some(Map("en" -> "Payments")),
          found.map(_.schema) == Some(newSchema),
        )
      },
      test("delete type") {
        for
          _ <- env.repository.createType(tenantId, paymentType, Map("en" -> "Payment initiation"), schema)
          _ <- env.repository.deleteType(tenantId, paymentType)
          found <- env.repository.findType(tenantId, paymentType)
        yield assertTrue(found.isEmpty)
      },
      test("getAll returns every registered type") {
        val accountType = AuthorizationDetailType("account_information")

        for
          _ <- env.repository.createType(tenantId, paymentType, Map("en" -> "Payment initiation"), schema)
          _ <- env.repository.createType(tenantId, accountType, Map("en" -> "Account information"), schema)
          all <- env.repository.getAll
        yield assertTrue(all.map(_.`type`).sorted == Vector(accountType, paymentType))
      },
    )

object AuthorizationDetailTypeRepositorySpec:
  case class Env(repository: AuthorizationDetailTypeRepository)
