package versola.central.users

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Phone}
import zio.json.*
import zio.test.*

import java.util.UUID

object OutboxEventSpec extends ZIOSpecDefault:

  private val userId   = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val version  = UUID.fromString("00000000-0000-0000-0000-000000000099")
  private val tenantId = TenantId("tenant-1")
  private val roleId   = RoleId("role-1")
  private val email    = Email("user@example.com")

  def spec = suite("OutboxEvent JSON")(
    test("UpsertUser round-trip") {
      val event = OutboxEvent.UpsertUser(
        userId = userId,
        version = version,
        email = Some(email),
        phone = None,
        login = Some(Login("user123")),
      )
      val json = event.toJson
      assertTrue(json.fromJson[OutboxEvent] == Right(event))
    },
    test("UpsertUser round-trip with all routing fields") {
      val event = OutboxEvent.UpsertUser(
        userId = userId,
        version = version,
        email = Some(email),
        phone = Some(Phone("+15551234567")),
        login = Some(Login("alice")),
      )
      val decoded = event.toJson.fromJson[OutboxEvent]
      assertTrue(decoded == Right(event))
    },
  )
