package versola.central.users

import versola.central.configuration.clients.{AuthFlow, ClientId, OAuthClientRecord, OAuthClientService}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone, RedirectUri, SecureRandom, UnitSpecBase}
import zio.*
import zio.durationInt
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

object UserServiceSpec extends UnitSpecBase:

  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val tenantId = TenantId("t1")
  private val email = Email("user@example.com")
  private val indexRecord = UserIndexRecord(userId, Some(email), None, None)

  class Env:
    val userRepository = stub[UserRepository]
    val authClient = stub[AuthClient]
    val oAuthClientService = stub[OAuthClientService]
    val secureRandom = stub[SecureRandom]
    val service = UserService.Impl(userRepository, authClient, oAuthClientService, secureRandom)

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
    test("indexRegistered returns the canonical repository id") {
      val env = Env()
      val request = RegisteredUserRequest(Some(email), None, None)
      for
        _ <- env.userRepository.indexFromAuth.succeedsWith(userId)
        result <- env.service.indexRegistered(request)
      yield assertTrue(result == userId)
    },
    test("getRoles delegates to authClient") {
      val env = Env()
      for
        _ <- env.authClient.getUserRoles.succeedsWith(List(RoleId("admin")))
        result <- env.service.getRoles(userId, tenantId)
      yield assertTrue(result == List(RoleId("admin")))
    },
    test("getSessions enriches client entries with expiresAt computed from client access token ttl") {
      val env = Env()
      val clientId = ClientId("web-app")
      val enteredAt = Instant.parse("2024-01-01T00:00:00Z")
      val ttl = 5.minutes
      val client = OAuthClientRecord(
        id = clientId,
        tenantId = tenantId,
        clientName = Map("en" -> "Web App"),
        redirectUris = Set(RedirectUri("https://example.com/callback")),
        scope = Set.empty,
        secret = None,
        previousSecret = None,
        accessTokenTtl = ttl,
        refreshTokenTtl = 7776000.seconds,
        permissions = Set.empty,
        theme = "",
        authFlow = Some(AuthFlow.default),
        registrationFlow = None,
        otpTemplateId = "default",
        frontChannelLogoutUri = None,
        frontChannelLogoutSessionRequired = false,
        backChannelLogoutUri = None,
        logoUri = None,
        policyUri = None,
        tosUri = None,
        consentFlow = None,
      )
      val sessionDto = AuthClient.SessionDto(
        publicId = "public-session-1",
        clients = List(AuthClient.ClientEntryDto(clientId, enteredAt)),
        platform = Some("desktop"),
        os = None,
        browser = None,
        version = None,
        createdAt = enteredAt.toString,
      )
      for
        _ <- env.authClient.getUserSessions.succeedsWith(List(sessionDto))
        _ <- env.oAuthClientService.getAllClients.succeedsWith(Vector(client))
        result <- env.service.getSessions(userId)
      yield assertTrue(
        result.size == 1,
        result.head.clients.head.clientId == clientId,
        result.head.clients.head.expiresAt == enteredAt.plus(ttl.asJava),
      )
    },
    test("getSessions sorts client entries by enteredAt descending (most recently issued first)") {
      val env = Env()
      val clientIdA = ClientId("web-app-a")
      val clientIdB = ClientId("web-app-b")
      val ttl = 5.minutes
      val olderEnteredAt = Instant.parse("2024-01-01T00:00:00Z")
      val newerEnteredAt = Instant.parse("2024-01-01T01:00:00Z")

      def client(id: ClientId) = OAuthClientRecord(
        id = id,
        tenantId = tenantId,
        clientName = Map("en" -> "Web App"),
        redirectUris = Set(RedirectUri("https://example.com/callback")),
        scope = Set.empty,
        secret = None,
        previousSecret = None,
        accessTokenTtl = ttl,
        refreshTokenTtl = 7776000.seconds,
        permissions = Set.empty,
        theme = "",
        authFlow = Some(AuthFlow.default),
        registrationFlow = None,
        otpTemplateId = "default",
        frontChannelLogoutUri = None,
        frontChannelLogoutSessionRequired = false,
        backChannelLogoutUri = None,
        logoUri = None,
        policyUri = None,
        tosUri = None,
        consentFlow = None,
      )
      val sessionDto = AuthClient.SessionDto(
        publicId = "public-session-1",
        clients = List(
          AuthClient.ClientEntryDto(clientIdA, olderEnteredAt),
          AuthClient.ClientEntryDto(clientIdB, newerEnteredAt),
        ),
        platform = Some("desktop"),
        os = None,
        browser = None,
        version = None,
        createdAt = olderEnteredAt.toString,
      )
      for
        _ <- env.authClient.getUserSessions.succeedsWith(List(sessionDto))
        _ <- env.oAuthClientService.getAllClients.succeedsWith(Vector(client(clientIdA), client(clientIdB)))
        result <- env.service.getSessions(userId)
      yield assertTrue(
        result.head.clients.map(_.clientId) == List(clientIdB, clientIdA),
      )
    },
    test("findByEmail returns enriched record when user found") {
      val env = Env()
      for
        _ <- env.userRepository.findByEmail.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUserClaims.succeedsWith(Some(Json.Obj()))
        result <- env.service.findByEmail(email)
      yield assertTrue(result.map(_.id).contains(userId))
    },
    test("findByPhone returns enriched record when user found") {
      val env = Env()
      val phone = Phone("+16502530000")
      val record = UserIndexRecord(userId, None, Some(phone), None)
      for
        _ <- env.userRepository.findByPhone.succeedsWith(Some(record))
        _ <- env.authClient.getUserClaims.succeedsWith(Some(Json.Obj()))
        result <- env.service.findByPhone(phone)
      yield assertTrue(result.map(_.id).contains(userId))
    },
    test("findByLogin returns enriched record when user found") {
      val env = Env()
      val login = Login("jdoe")
      val record = UserIndexRecord(userId, None, None, Some(login))
      for
        _ <- env.userRepository.findByLogin.succeedsWith(Some(record))
        _ <- env.authClient.getUserClaims.succeedsWith(Some(Json.Obj()))
        result <- env.service.findByLogin(login)
      yield assertTrue(result.map(_.id).contains(userId))
    },
    test("findById defaults to an empty claims object when auth has none for the user") {
      val env = Env()
      for
        _ <- env.userRepository.findById.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUserClaims.succeedsWith(None)
        result <- env.service.findById(userId)
      yield assertTrue(result.map(_.claims).contains(Json.Obj()))
    },
    test("invalidateSession delegates to authClient") {
      val env = Env()
      for
        _ <- env.authClient.invalidateSession.succeedsWith(())
        _ <- env.service.invalidateSession(userId)
      yield assertTrue(env.authClient.invalidateSession.calls == List(userId))
    },
    test("create fails with UserConflict when repository reports a conflict") {
      val env = Env()
      val newId = UUID.fromString("00000000-0000-0000-0000-000000000003")
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(newId)
        _ <- env.userRepository.create.failsWith(UserConflict)
        result <- env.service.create(CreateUserRequest(Some(email), None, None)).either
      yield assertTrue(result == Left(UserConflict))
    },
    test("indexRegistered fails with UserIndexConflict when credentials resolve to multiple users") {
      val env = Env()
      val request = RegisteredUserRequest(Some(email), None, None)
      for
        _ <- env.userRepository.indexFromAuth.failsWith(UserIndexConflict)
        result <- env.service.indexRegistered(request).either
      yield assertTrue(result == Left(UserIndexConflict))
    },
    test("patch delegates request fields to repository") {
      val env = Env()
      val request = PatchUserRequest(userId, Some(Patch.Modified(email)), None, None)
      for
        _ <- env.userRepository.patch.succeedsWith(())
        _ <- env.service.patch(request)
      yield assertTrue(
        env.userRepository.patch.calls == List((userId, Some(Patch.Modified(email)), None, None)),
      )
    },
    test("patchClaims delegates to authClient") {
      val env = Env()
      val patch = Json.Obj("test" -> Json.Bool(true))
      for
        _ <- env.authClient.patchUserClaims.succeedsWith(())
        _ <- env.service.patchClaims(userId, patch)
      yield assertTrue(env.authClient.patchUserClaims.calls == List((userId, patch)))
    },
    test("delete delegates to repository") {
      val env = Env()
      for
        _ <- env.userRepository.delete.succeedsWith(())
        _ <- env.service.delete(userId)
      yield assertTrue(env.userRepository.delete.calls == List(userId))
    },
    test("updateRoles enqueues a role update via repository") {
      val env = Env()
      val request = UpdateUserRolesRequest(userId, tenantId, Set(RoleId("admin")), Set(RoleId("viewer")))
      for
        _ <- env.userRepository.enqueueRoleUpdate.succeedsWith(())
        _ <- env.service.updateRoles(request)
      yield assertTrue(
        env.userRepository.enqueueRoleUpdate.calls ==
          List((userId, tenantId, Set(RoleId("admin")), Set(RoleId("viewer")))),
      )
    },
    test("resetLimits delegates request fields to authClient") {
      val env = Env()
      val request = ResetUserLimitsRequest(userId, tenantId, Some(email), None)
      for
        _ <- env.authClient.resetUserLimits.succeedsWith(())
        _ <- env.service.resetLimits(request)
      yield assertTrue(
        env.authClient.resetUserLimits.calls == List((userId, tenantId, Some(email), None)),
      )
    },
    test("listPasskeys delegates to authClient") {
      val env = Env()
      val passkey = PasskeyInfo(
        id = "cred-1",
        name = Some("Phone"),
        deviceType = "MultiDevice",
        transports = List("internal"),
        backedUp = true,
        backupEligible = true,
        lastUsedAt = None,
        createdAt = "2024-01-01T00:00:00Z",
      )
      for
        _ <- env.authClient.listPasskeys.succeedsWith(List(passkey))
        result <- env.service.listPasskeys(userId)
      yield assertTrue(result == List(passkey))
    },
    test("renamePasskey delegates request fields to authClient") {
      val env = Env()
      val request = RenamePasskeyRequest(userId, "cred-1", Some("New name"))
      for
        _ <- env.authClient.renamePasskey.succeedsWith(())
        _ <- env.service.renamePasskey(request)
      yield assertTrue(env.authClient.renamePasskey.calls == List((userId, "cred-1", Some("New name"))))
    },
    test("deletePasskey delegates to authClient") {
      val env = Env()
      for
        _ <- env.authClient.deletePasskey.succeedsWith(())
        _ <- env.service.deletePasskey(userId, "cred-1")
      yield assertTrue(env.authClient.deletePasskey.calls == List((userId, "cred-1")))
    },
    test("resetPassword returns the plaintext password when authClient provides one") {
      val env = Env()
      val request = ResetPasswordRequest(userId, 43200L, Some(DeliveryChannel.show))
      for
        _ <- env.authClient.resetPassword.succeedsWith(Some("Temp1234!"))
        result <- env.service.resetPassword(request)
      yield assertTrue(
        result.contains("Temp1234!"),
        env.authClient.resetPassword.calls == List((userId, 43200L, Some(DeliveryChannel.show))),
      )
    },
    test("resetPassword returns None when the password is delivered out of band") {
      val env = Env()
      val request = ResetPasswordRequest(userId, 43200L, Some(DeliveryChannel.email))
      for
        _ <- env.authClient.resetPassword.succeedsWith(None)
        result <- env.service.resetPassword(request)
      yield assertTrue(result.isEmpty)
    },
    test("setPassword delegates to authClient") {
      val env = Env()
      for
        _ <- env.authClient.setPassword.succeedsWith(())
        _ <- env.service.setPassword(userId, "Secret123!")
      yield assertTrue(env.authClient.setPassword.calls == List((userId, "Secret123!")))
    },
  )
