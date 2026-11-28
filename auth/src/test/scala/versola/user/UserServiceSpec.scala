package versola.user

import versola.audit.AuthAuditKinds
import versola.audit.{AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents}
import versola.auth.TestEnvConfig
import versola.role.model.RoleId
import versola.user.model.*
import versola.util.{CoreConfig, Email, Phone, RoutingKey, UnitSpecBase}
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

  class Env(val config: CoreConfig = TestEnvConfig.cellCoreConfig):
    val userRepository = stub[UserRepository]
    val directory = stub[UserDirectory]
    val audit = CapturingAuditEvents.shared()
    val service = UserService.Impl(userRepository, directory, config, audit)

  def spec = suite("UserService")(
    test("creates a user and assigns registration roles") {
      val env = Env()
      for
        _ <- env.directory.claim.succeedsWith(UserDirectory.Claim(user.id, Some(TestEnvConfig.cellId)))
        _ <- env.userRepository.register.succeedsWith(user)
        result <- env.service.register(Left(email), tenantId, roleIds)
        registerCalls = env.userRepository.register.calls
        drafts <- env.audit.recorded
      yield assertTrue(
        // account.registered: about the new user, by registration, and the address they registered with is not in it
        drafts.map(_.kind) == List(AuthAuditKinds.AccountRegistered),
        drafts.head.userId == Some(user.id.asInstanceOf[java.util.UUID]),
        drafts.head.details == AuthAuditDetails.Account(via = "registration", detail = None),
        !drafts.head.details.toJson.toString.contains(email),
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
        _ <- env.directory.claim.succeedsWith(UserDirectory.Claim(phoneUser.id, None))
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
    // versolauth/versola#1041: the claim says which cell holds the user of the credential.
    test("does not create a user here when the credential belongs to a user of another cell") {
      val env = Env()
      val elsewhere = RoutingKey.CellId.parse("kz-2").toOption.get
      for
        _ <- env.directory.claim.succeedsWith(UserDirectory.Claim(user.id, Some(elsewhere)))
        result <- env.service.register(Left(email), tenantId, roleIds).either
        drafts <- env.audit.recorded
      yield assertTrue(
        result == Left(UserService.HomeCellElsewhere(elsewhere)),
        env.userRepository.register.calls.isEmpty,
        drafts.isEmpty,
      )
    },
    // versolauth/versola#1052: this cell takes no users; the caller hands the registration on, nothing is created here.
    test("does not create a user here when central refuses the claim because this cell takes no users, or no cell does") {
      val env = Env()
      val taking = RoutingKey.CellId.parse("kz-2").toOption.get
      for
        _ <- env.directory.claim.failsWith(UserDirectory.CellNotTaking(taking))
        refused <- env.service.register(Left(email), tenantId, roleIds).either
        _ <- env.directory.claim.failsWith(UserDirectory.NoCellTakesUsers)
        none <- env.service.register(Left(email), tenantId, roleIds).either
        drafts <- env.audit.recorded
      yield assertTrue(
        refused == Left(UserDirectory.CellNotTaking(taking)),
        none == Left(UserDirectory.NoCellTakesUsers),
        env.userRepository.register.calls.isEmpty,
        drafts.isEmpty,
      )
    },
    test("an auth that is no cell creates the user whatever the claim says of cells, as before cells existed") {
      val env = Env(TestEnvConfig.coreConfig)
      for
        _ <- env.directory.claim.succeedsWith(UserDirectory.Claim(user.id, None))
        _ <- env.userRepository.register.succeedsWith(user)
        result <- env.service.register(Left(email), tenantId, roleIds)
      yield assertTrue(result == user)
    },
    test("does not create an auth user when the central claim fails") {
      val env = Env()
      val error = RuntimeException("central unavailable")
      for
        _ <- env.directory.claim.failsWith(error)
        result <- env.service.register(Left(email), tenantId, roleIds).either
        drafts <- env.audit.recorded
      yield assertTrue(
        result == Left(error),
        env.userRepository.register.calls.isEmpty,
        drafts.isEmpty,
      )
    },
  )
