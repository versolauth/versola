package versola.user

import versola.role.model.RoleId
import versola.user.model.*
import versola.util.{Email, Phone, UnitSpecBase}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

object UserServiceSpec extends UnitSpecBase:
  private val userId = UserId(UUID.randomUUID())
  private val tenantId = versola.oauth.client.model.TenantId("tenant-1")
  private val email = Email("new@example.com")
  private val phone = Phone("+12025551234")
  private val roleIds = Set(RoleId("user"), RoleId("member"))
  private val user = UserRecord(userId, Some(email), None, None, Json.Obj(), None)

  class Env:
    val userRepository = stub[UserRepository]
    val registrationSyncClient = stub[UserRegistrationSyncClient]
    val service = UserService.Impl(userRepository, registrationSyncClient)

  def spec = suite("UserService")(
    test("creates a user and assigns registration roles") {
      val env = Env()
      for
        _ <- env.registrationSyncClient.claimRegistration.succeedsWith(user.id)
        _ <- env.userRepository.register.succeedsWith(user)
        result <- env.service.register(Left(email), tenantId, roleIds)
        registerCalls = env.userRepository.register.calls
      yield assertTrue(
        result == user,
        registerCalls.map(_._1) == List(user.id),
        registerCalls.map(_._2) == List(Left(email)),
        registerCalls.map(_._3) == List(tenantId),
        registerCalls.map(_._4) == List(roleIds),
      )
    },
    test("registers a phone credential and assigns registration roles") {
      val env = Env()
      val phoneUser = user.copy(email = None, phone = Some(phone))
      for
        _ <- env.registrationSyncClient.claimRegistration.succeedsWith(phoneUser.id)
        _ <- env.userRepository.register.succeedsWith(phoneUser)
        result <- env.service.register(Right(phone), tenantId, roleIds)
        repositoryCalls = env.userRepository.register.calls
      yield assertTrue(
        result == phoneUser,
        repositoryCalls.map(_._1) == List(phoneUser.id),
        repositoryCalls.map(_._2) == List(Right(phone)),
        repositoryCalls.map(_._3) == List(tenantId),
        repositoryCalls.map(_._4) == List(roleIds),
      )
    },
    test("does not create an auth user when the central claim fails") {
      val env = Env()
      val error = RuntimeException("central unavailable")
      for
        _ <- env.registrationSyncClient.claimRegistration.failsWith(error)
        result <- env.service.register(Left(email), tenantId, roleIds).either
      yield assertTrue(
        result == Left(error),
        env.userRepository.register.calls.isEmpty,
      )
    },
  )