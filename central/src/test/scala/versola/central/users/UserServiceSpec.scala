package versola.central.users

import versola.central.configuration.clients.{AuthFlow, ClientId, OAuthClientRecord, OAuthClientService}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, RedirectUri, SecureRandom, UnitSpecBase}
import zio.*
import zio.durationInt
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

object UserServiceSpec extends UnitSpecBase:

  private val userId   = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val tenantId = TenantId("t1")
  private val email    = Email("user@example.com")
  private val indexRecord = UserIndexRecord(userId, Some(email), None, None)

  class Env:
    val userRepository     = stub[UserRepository]
    val authClient         = stub[AuthClient]
    val oAuthClientService = stub[OAuthClientService]
    val secureRandom       = stub[SecureRandom]
    val service            = UserService.Impl(userRepository, authClient, oAuthClientService, secureRandom)

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
  )
