package versola.central.users

import versola.util.{Email, Patch, Phone}
import zio.json.*
import zio.test.*

import java.util.UUID

object PatchUserRequestSpec extends ZIOSpecDefault:

  private val id    = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val email = Email("user@example.com")
  private val phone = Phone("+12025550123")
  private val login = Login("user123")

  private val idStr   = "00000000-0000-0000-0000-000000000001"

  /** Only `id` present; all patchable fields absent. */
  private val minimalJson =
    s"""{"id":"$idStr"}"""

  /** All patchable fields present as `null`. */
  private val nullsJson =
    s"""{"id":"$idStr","email":null,"phone":null,"login":null}"""

  /** All patchable fields present with values. */
  private val valuesJson =
    s"""{"id":"$idStr","email":"user@example.com","phone":"+12025550123","login":"user123"}"""

  def spec = suite("PatchUserRequest JSON")(
    suite("decode")(
      test("absent fields → None (no update)") {
        val result = minimalJson.fromJson[PatchUserRequest]
        assertTrue(result == Right(PatchUserRequest(id, None, None, None, None)))
      },
      test("null fields → Some(Deleted) (clear field)") {
        val result = nullsJson.fromJson[PatchUserRequest]
        assertTrue(result == Right(PatchUserRequest(
          id,
          Some(Patch.Deleted),
          Some(Patch.Deleted),
          Some(Patch.Deleted),
          None,
        )))
      },
      test("value fields → Some(Modified) (update field)") {
        val result = valuesJson.fromJson[PatchUserRequest]
        assertTrue(result == Right(PatchUserRequest(
          id,
          Some(Patch.Modified(email)),
          Some(Patch.Modified(phone)),
          Some(Patch.Modified(login)),
          None,
        )))
      },
    ),
    suite("encode")(
      test("None → key absent") {
        assertTrue(PatchUserRequest(id, None, None, None, None).toJson == minimalJson)
      },
      test("Some(Deleted) → null value") {
        val request = PatchUserRequest(id, Some(Patch.Deleted), Some(Patch.Deleted), Some(Patch.Deleted), None)
        assertTrue(request.toJson == nullsJson)
      },
      test("Some(Modified) → present value") {
        val request = PatchUserRequest(
          id,
          Some(Patch.Modified(email)),
          Some(Patch.Modified(phone)),
          Some(Patch.Modified(login)),
          None,
        )
        assertTrue(request.toJson == valuesJson)
      },
    ),
  )
