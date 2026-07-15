package versola.central.users

import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, SecureRandom, UnitSpecBase}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

object UserServiceSpec extends UnitSpecBase:

  private val userId   = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val tenantId = TenantId("t1")
  private val email    = Email("user@example.com")
  private val indexRecord = UserIndexRecord(userId, Some(email), None, None)

  class Env:
    val userRepository = stub[UserRepository]
    val authClient     = stub[AuthClient]
    val secureRandom   = stub[SecureRandom]
    val service        = UserService.Impl(userRepository, authClient, secureRandom)

  def spec = suite("UserService")(
    test("findById returns enriched record when user found") {
      val env = Env()
      for
        _ <- env.userRepository.findById.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUserClaims.succeedsWith(Some(Json.Obj()))
        result <- env.service.findById(userId)
      yield assertTrue(result.map(_.id).contains(userId))
    },
    test("findById returns None when user not found") {
      val env = Env()
      for
        _ <- env.userRepository.findById.succeedsWith(None)
        result <- env.service.findById(userId)
      yield assertTrue(result.isEmpty)
    },
    test("create inserts user and returns generated id") {
      val env = Env()
      val newId = UUID.fromString("00000000-0000-0000-0000-000000000002")
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(newId)
        _ <- env.userRepository.create.succeedsWith(())
        result <- env.service.create(CreateUserRequest(Some(email), None, None))
      yield assertTrue(result == UserId(newId))
    },
    test("getRoles delegates to authClient") {
      val env = Env()
      for
        _ <- env.authClient.getUserRoles.succeedsWith(List(RoleId("admin")))
        result <- env.service.getRoles(userId, tenantId)
      yield assertTrue(result == List(RoleId("admin")))
    },
  )
