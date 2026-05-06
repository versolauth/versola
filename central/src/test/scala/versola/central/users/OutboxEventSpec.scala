package versola.central.users

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

object OutboxEventSpec extends ZIOSpecDefault:

  private val userId   = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val tenantId = TenantId("tenant-1")
  private val roleId   = RoleId("role-1")
  private val email    = Email("user@example.com")

  def spec = suite("OutboxEvent JSON")(
    test("CreateUser round-trip") {
      val event = OutboxEvent.CreateUser(
        id = userId,
        email = Some(email),
        phone = None,
        login = Some(Login("user123")),
        claims = Json.Obj("key" -> Json.Str("value")),
      )
      val json = event.toJson
      assertTrue(json.fromJson[OutboxEvent] == Right(event))
    },
    test("CreateUser round-trip preserves boolean and nested claims") {
      val claims = Json.Obj(
        "email_verified"        -> Json.Bool(true),
        "phone_number_verified" -> Json.Bool(false),
        "given_name"            -> Json.Str("Alice"),
        "address"               -> Json.Obj("country" -> Json.Str("US")),
      )
      val event = OutboxEvent.CreateUser(
        id = userId,
        email = Some(email),
        phone = Some(Phone("+15551234567")),
        login = Some(Login("alice")),
        claims = claims,
      )
      val decoded = event.toJson.fromJson[OutboxEvent]
      assertTrue(
        decoded == Right(event),
        decoded.toOption.map(_.asInstanceOf[OutboxEvent.CreateUser].claims) == Some(claims),
      )
    },
    test("PatchUser round-trip with Deleted and Modified") {
      val event = OutboxEvent.PatchUser(
        id = userId,
        email = Some(Patch.Deleted),
        phone = Some(Patch.Modified(Phone("+123456789"))),
        login = None,
        claims = Some(Json.Obj("foo" -> Json.Str("bar"))),
      )
      val json = event.toJson
      // Verify that Patch.Deleted is encoded as null and preserved on decode
      assertTrue(json.contains("\"email\":null"))
      assertTrue(json.fromJson[OutboxEvent] == Right(event))
    },
    test("PatchUser round-trip with all None") {
      val event = OutboxEvent.PatchUser(
        id = userId,
        email = None,
        phone = None,
        login = None,
        claims = None,
      )
      val json = event.toJson
      // None should be omitted from JSON
      assertTrue(!json.contains("\"email\""))
      assertTrue(json.fromJson[OutboxEvent] == Right(event))
    },
    test("AssignRole round-trip") {
      val event = OutboxEvent.AssignRole(userId, tenantId, roleId)
      val json = event.toJson
      assertTrue(json.fromJson[OutboxEvent] == Right(event))
    },
    test("RemoveRole round-trip") {
      val event = OutboxEvent.RemoveRole(userId, tenantId, roleId)
      val json = event.toJson
      assertTrue(json.fromJson[OutboxEvent] == Right(event))
    },
  )
