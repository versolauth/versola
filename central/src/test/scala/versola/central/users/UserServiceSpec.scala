package versola.central.users

import versola.central.configuration.clients.ApplicationType
import versola.central.configuration.clients.{AuthFlow, AuthMethod, ClientApproval, ClientId, OAuthClientRecord, OAuthClientService}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.topology.CellId
import versola.util.{Email, Patch, Phone, RedirectUri, Secret, SecureRandom, UnitSpecBase}
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
  private val indexRecord = UserDirectoryRecord(userId, cellId = None)
  private val key = DirectoryKey.derive(Secret(Array.fill(16)(7.toByte)))
  private val cell = CellId("kz-1")
  private def held(email: Option[Email] = None, phone: Option[Phone] = None, login: Option[Login] = None) =
    AuthClient.UserDto(email = email, phone = phone, login = login, claims = Json.Obj())

  class Env:
    val userRepository = stub[UserRepository]
    val authClient = stub[AuthClient]
    val oAuthClientService = stub[OAuthClientService]
    val secureRandom = stub[SecureRandom]
    val service = UserService.Impl(userRepository, authClient, oAuthClientService, secureRandom, key)

  def spec = suite("UserService")(
    test("findById returns enriched record when user found") {
      val env = Env()
      for
        _ <- env.userRepository.find.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUser.succeedsWith(Some(held(email = Some(email))))
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector.empty)
        result <- env.service.findById(userId)
      yield assertTrue(result.map(_.id).contains(userId))
    },
    test("findById returns None when user not found") {
      val env = Env()
      for
        _ <- env.userRepository.find.succeedsWith(None)
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
    // versolauth/versola#1041
    test("indexRegistered claims the MAC of the credential for a new id in the caller's cell") {
      val env = Env()
      val minted = UUID.fromString("00000000-0000-0000-0000-000000000009")
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(minted)
        _ <- env.userRepository.claim.returnsZIO((_, candidate) => ZIO.succeed(candidate))
        result <-
          env.service.indexRegistered(RegisteredUserRequest(email = None, phone = Some(Phone("+15551234567")), login = None, cell = Some(cell)))
        calls = env.userRepository.claim.calls
      yield assertTrue(
        result == RegisteredUserResponse(userId = UserId(minted), cell = Some(cell)),
        calls.map(_._2) == List(UserDirectoryRecord(UserId(minted), Some(cell))),
        calls.map(_._1.map(_.kind)) == List(List(IdentifierKind.Phone)),
        // Only the MAC leaves the service, and it is the one a search for the same phone computes.
        calls.flatMap(_._1.map(_.mac.toList)) == List(key.phone(Phone("+15551234567")).mac.toList),
      )
    },
    test("indexRegistered answers the owner and the owner's cell for a credential claimed before, whatever cell asks") {
      val env = Env()
      val owner = UserDirectoryRecord(userId, Some(CellId("kz-2")))
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(UUID.fromString("00000000-0000-0000-0000-000000000009"))
        _ <- env.userRepository.claim.succeedsWith(owner)
        result <- env.service.indexRegistered(RegisteredUserRequest(email = Some(email), phone = None, login = None, cell = Some(cell)))
      yield assertTrue(result == RegisteredUserResponse(userId = userId, cell = Some(CellId("kz-2"))))
    },
    test("the MAC of an identifier depends on its kind and on the key, and is the same for the same value") {
      val other = DirectoryKey.derive(Secret(Array.fill(16)(8.toByte)))
      val phone = Phone("+15551234567")
      assertTrue(
        key.phone(phone).mac.toList == key.phone(phone).mac.toList,
        key.phone(phone).mac.toList != other.phone(phone).mac.toList,
        key.phone(phone).mac.toList != key.login(Login("+15551234567")).mac.toList,
        key.phone(phone).mac.length == 32,
      )
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
        otpTemplateId = "default",
        frontChannelLogoutUri = None,
        frontChannelLogoutSessionRequired = false,
        backChannelLogoutUri = None,
        logoUri = None,
        policyUri = None,
        tosUri = None,
        consentFlow = None,
        dpopBoundAccessTokens = false,
        dpopSigningAlgs = Set.empty,
        dpopMinRsaKeySize = None,
        authMethod = AuthMethod.client_secret,
        mtlsAuth = None,
        certificateBoundAccessTokens = false,
        jwks = None,
        requireSignedRequestObject = false,
        requirePushedAuthorizationRequests = false,
        edgeSigningKey = None,
        edgeClientCertificate = None,
        template = None,
        createdAt = Instant.EPOCH,
        themeOverrides = None,
        applicationType = ApplicationType.web,
        partnerId = None,
        approval = ClientApproval.Approved,
      )
      val sessionDto = AuthClient.SessionDto(
        publicId = "public-session-1",
        clients = List(AuthClient.ClientEntryDto(clientId, enteredAt)),
        platform = Some("desktop"),
        os = None,
        browser = None,
        version = None,
        createdAt = enteredAt.toString,
        expiresAt = enteredAt.plusSeconds(3600),
        idleExpiresAt = None,
        userAgent = None,
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
    test("getSessions passes the session's expiry, idle expiry and user agent through") {
      val env = Env()
      val created = Instant.parse("2024-01-01T00:00:00Z")
      val agent = AuthClient.UserAgentDto(
        id = "00000000-0000-0000-0000-0000000000a1",
        userAgent = Some("raw"),
        platform = Some("desktop"),
        os = Some("macOS 14.6"),
        browser = Some("Chrome"),
        version = Some("130"),
        createdAt = created,
        expiresAt = created.plusSeconds(86400),
      )
      val sessionDto = AuthClient.SessionDto(
        publicId = "public-session-1",
        clients = Nil,
        platform = Some("desktop"),
        os = Some("macOS 14.6"),
        browser = Some("Chrome"),
        version = Some("130"),
        createdAt = created.toString,
        expiresAt = created.plusSeconds(3600),
        idleExpiresAt = Some(created.plusSeconds(600)),
        userAgent = Some(agent),
      )
      for
        _ <- env.authClient.getUserSessions.succeedsWith(List(sessionDto))
        _ <- env.oAuthClientService.getAllClients.succeedsWith(Vector.empty)
        result <- env.service.getSessions(userId)
      yield assertTrue(
        result.map(_.expiresAt) == List(created.plusSeconds(3600)),
        result.map(_.idleExpiresAt) == List(Some(created.plusSeconds(600))),
        result.flatMap(_.userAgent).map(_.id) == List(agent.id),
        result.flatMap(_.userAgent).flatMap(_.userAgent) == List("raw"),
        result.flatMap(_.userAgent).map(_.expiresAt) == List(created.plusSeconds(86400)),
      )
    },
    test("getDevices maps a signed-in device with its session and a signed-out one with no session") {
      val env = Env()
      val created = Instant.parse("2024-01-01T00:00:00Z")
      def agent(id: String, raw: Option[String]) = AuthClient.UserAgentDto(
        id = id,
        userAgent = raw,
        platform = Some("ios"),
        os = Some("iOS 17"),
        browser = Some("Safari"),
        version = Some("17"),
        createdAt = created,
        expiresAt = created.plusSeconds(86400),
      )
      val signedIn = AuthClient.DeviceDto(
        userAgent = Some(agent("ua-1", Some("raw-1"))),
        session = Some(
          AuthClient.SessionDto(
            publicId = "public-session-1",
            clients = Nil,
            platform = Some("ios"),
            os = Some("iOS 17"),
            browser = Some("Safari"),
            version = Some("17"),
            createdAt = created.toString,
            expiresAt = created.plusSeconds(3600),
            idleExpiresAt = None,
            userAgent = Some(agent("ua-1", Some("raw-1"))),
          ),
        ),
        endedSessionIds = Nil,
      )
      val signedOut = AuthClient.DeviceDto(userAgent = Some(agent("ua-2", None)), session = None, endedSessionIds = List("old-1", "old-2"))
      for
        _ <- env.authClient.getUserDevices.succeedsWith(List(signedIn, signedOut))
        _ <- env.oAuthClientService.getAllClients.succeedsWith(Vector.empty)
        result <- env.service.getDevices(userId)
      yield assertTrue(
        env.authClient.getUserDevices.calls == List(userId),
        result.map(_.userAgent.map(_.id)) == List(Some("ua-1"), Some("ua-2")),
        result.map(_.session.map(_.publicId)) == List(Some("public-session-1"), None),
        result.head.session.map(_.expiresAt).contains(created.plusSeconds(3600)),
        result.head.session.flatMap(_.idleExpiresAt).isEmpty,
        result.head.userAgent.flatMap(_.userAgent).contains("raw-1"),
        result(1).userAgent.flatMap(_.userAgent).isEmpty,
        result(1).userAgent.map(_.expiresAt).contains(created.plusSeconds(86400)),
        result(1).endedSessionIds == List("old-1", "old-2"),
      )
    },
    test("forgetUserAgent delegates to authClient and answers whether it was forgotten") {
      val env = Env()
      for
        _ <- env.authClient.forgetUserAgent.succeedsWith(false)
        forgotten <- env.service.forgetUserAgent(userId, "ua-1")
      yield assertTrue(!forgotten, env.authClient.forgetUserAgent.calls == List((userId, "ua-1")))
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
        otpTemplateId = "default",
        frontChannelLogoutUri = None,
        frontChannelLogoutSessionRequired = false,
        backChannelLogoutUri = None,
        logoUri = None,
        policyUri = None,
        tosUri = None,
        consentFlow = None,
        dpopBoundAccessTokens = false,
        dpopSigningAlgs = Set.empty,
        dpopMinRsaKeySize = None,
        authMethod = AuthMethod.client_secret,
        mtlsAuth = None,
        certificateBoundAccessTokens = false,
        jwks = None,
        requireSignedRequestObject = false,
        requirePushedAuthorizationRequests = false,
        edgeSigningKey = None,
        edgeClientCertificate = None,
        template = None,
        createdAt = Instant.EPOCH,
        themeOverrides = None,
        applicationType = ApplicationType.web,
        partnerId = None,
        approval = ClientApproval.Approved,
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
        expiresAt = olderEnteredAt.plusSeconds(3600),
        idleExpiresAt = None,
        userAgent = None,
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
        _ <- env.userRepository.findByIdentifier.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUser.succeedsWith(Some(held(email = Some(email))))
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector.empty)
        result <- env.service.findByEmail(email)
      yield assertTrue(result.map(_.id).contains(userId))
    },
    test("findByPhone returns enriched record when user found") {
      val env = Env()
      val phone = Phone("+16502530000")
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUser.succeedsWith(Some(held(email = Some(email))))
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector.empty)
        result <- env.service.findByPhone(phone)
      yield assertTrue(
        result.map(_.id).contains(userId),
        // The directory is asked by the MAC, and the identifiers shown are what auth holds.
        env.userRepository.findByIdentifier.calls.map(i => (i.kind, i.mac.toList)) == List((IdentifierKind.Phone, key.phone(phone).mac.toList)),
        result.flatMap(_.email).contains(email),
      )
    },
    test("findByLogin returns enriched record when user found") {
      val env = Env()
      val login = Login("jdoe")
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUser.succeedsWith(Some(held(email = Some(email))))
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector.empty)
        result <- env.service.findByLogin(login)
      yield assertTrue(result.map(_.id).contains(userId))
    },
    test("findById defaults to an empty claims object when auth has none for the user") {
      val env = Env()
      for
        _ <- env.userRepository.find.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUser.succeedsWith(None)
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector.empty)
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
    test("endSession delegates to authClient and answers whether the session was ended") {
      val env = Env()
      for
        _ <- env.authClient.endSession.succeedsWith(false)
        ended <- env.service.endSession(userId, "c2lk")
      yield assertTrue(!ended, env.authClient.endSession.calls == List((userId, "c2lk")))
    },
    test("revokeRefreshTokens delegates to authClient") {
      val env = Env()
      for
        _ <- env.authClient.revokeRefreshTokens.succeedsWith(())
        _ <- env.service.revokeRefreshTokens(userId, ClientId("app"))
      yield assertTrue(env.authClient.revokeRefreshTokens.calls == List((userId, ClientId("app"))))
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
      val request = RegisteredUserRequest(email = Some(email), phone = None, login = None, cell = None)
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(UUID.fromString("00000000-0000-0000-0000-000000000009"))
        _ <- env.userRepository.claim.failsWith(UserIndexConflict)
        result <- env.service.indexRegistered(request).either
      yield assertTrue(result == Left(UserIndexConflict))
    },
    test("a user is read back as it was just written: what is queued for auth is applied over what auth holds") {
      val env = Env()
      val version = UUID.fromString("00000000-0000-0000-0000-000000000007")
      val phone = Phone("+15551234567")
      for
        _ <- env.userRepository.find.succeedsWith(Some(indexRecord))
        // auth has not been given the user yet
        _ <- env.authClient.getUser.succeedsWith(None)
        _ <- env.userRepository.pendingEvents.succeedsWith(
          Vector(
            OutboxEvent.UpsertUser(userId = userId, version = version, email = Some(email), phone = None, login = Some(Login("jdoe"))),
            OutboxEvent.PatchUser(userId = userId, version = version, email = None, phone = Some(Patch.Modified(phone)), login = Some(Patch.Deleted)),
          ),
        )
        created <- env.service.findById(userId)
        // auth has the user, and a deletion is queued
        _ <- env.authClient.getUser.succeedsWith(Some(held(email = Some(email))))
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector(OutboxEvent.DeleteUser(userId)))
        deleted <- env.service.findById(userId)
      yield assertTrue(
        created.flatMap(_.email).contains(email),
        created.flatMap(_.phone).contains(phone),
        created.exists(_.login.isEmpty),
        deleted.exists(u => u.email.isEmpty && u.phone.isEmpty),
      )
    },
    test("patch replaces the MAC of what changed, removes what was deleted, and tells auth only what it named") {
      val env = Env()
      val version = UUID.fromString("00000000-0000-0000-0000-000000000007")
      val request = PatchUserRequest(userId, Some(Patch.Modified(email)), Some(Patch.Deleted), None)
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(version)
        _ <- env.userRepository.replaceIdentifiers.succeedsWith(())
        _ <- env.service.patch(request)
        calls = env.userRepository.replaceIdentifiers.calls
      yield assertTrue(
        calls.map(_._1) == List(userId),
        calls.map(_._2.map(i => (i.kind, i.mac.toList))) == List(List((IdentifierKind.Email, key.email(email).mac.toList))),
        calls.map(_._3) == List(Set(IdentifierKind.Phone)),
        calls.map(_._4) == List(OutboxEvent.PatchUser(
          userId = userId,
          version = version,
          email = Some(Patch.Modified(email)),
          phone = Some(Patch.Deleted),
          login = None,
        )),
      )
    },
    test("patch fails with UserConflict when the identifier belongs to another user") {
      val env = Env()
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(UUID.fromString("00000000-0000-0000-0000-000000000007"))
        _ <- env.userRepository.replaceIdentifiers.failsWith(UserConflict)
        result <- env.service.patch(PatchUserRequest(userId, Some(Patch.Modified(email)), None, None)).either
      yield assertTrue(result == Left(UserConflict))
    },
    test("patchClaims delegates to authClient") {
      val env = Env()
      val patch = Json.Obj("test" -> Json.Bool(true))
      for
        _ <- env.authClient.patchUserClaims.succeedsWith(())
        _ <- env.service.patchClaims(userId, patch)
      yield assertTrue(env.authClient.patchUserClaims.calls == List((userId, patch)))
    },
    test("delete purges the user in auth before removing the index row") {
      val env = Env()
      for
        order <- Ref.make(List.empty[String])
        _ <- env.userRepository.find.succeedsWith(Some(indexRecord))
        _ <- env.authClient.deleteUser.returnsZIO(_ => order.update(_ :+ "auth"))
        _ <- env.userRepository.delete.returnsZIO(_ => order.update(_ :+ "index"))
        _ <- env.service.delete(userId)
        seen <- order.get
      yield assertTrue(
        seen == List("auth", "index"),
        env.authClient.deleteUser.calls == List(userId),
        env.userRepository.delete.calls == List(userId),
      )
    },
    test("delete fails with UserNotFound for an id that is not indexed and touches nothing") {
      val env = Env()
      for
        _ <- env.userRepository.find.succeedsWith(None)
        exit <- env.service.delete(userId).exit
      yield assertTrue(
        exit == Exit.fail(UserNotFound),
        env.authClient.deleteUser.calls.isEmpty,
        env.userRepository.delete.calls.isEmpty,
      )
    },
    test("delete leaves the index row in place when auth's purge fails") {
      val env = Env()
      val failure = RuntimeException("auth unavailable")
      for
        _ <- env.userRepository.find.succeedsWith(Some(indexRecord))
        _ <- env.authClient.deleteUser.failsWith(failure)
        exit <- env.service.delete(userId).exit
      yield assertTrue(
        exit == Exit.fail(failure),
        env.userRepository.delete.calls.isEmpty,
      ).label("a user auth still knows must stay findable, or the deletion could never be retried")
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
