package versola.oauth.userinfo

import org.scalamock.stubs.ZIOStubs
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthMethod, Claim, ClaimRecord, ClientId, ConsentFlow, OAuthClientRecord, ScopeRecord, ScopeToken, TenantId}
import versola.oauth.model.Nonce
import versola.oauth.userinfo.model.{ClaimRequest, RequestedClaims, UserInfoError}
import versola.user.UserRepository
import versola.user.model.{UserId, UserRecord}
import versola.util.{Email, UnitSpecBase}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

object UserInfoServiceSpec extends UnitSpecBase, ZIOStubs:
  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val email1 = Email("john@example.com")
  val testUser = UserRecord(
    userId1,
    Some(email1),
    None,
    None,
    Json.Obj("name" -> Json.Str("John Doe"), "given_name" -> Json.Str("John"), "family_name" -> Json.Str("Doe")),
    None,
  )

  private def scope(id: ScopeToken, claims: Claim*) =
    ScopeRecord(id, Map.empty, claims.toVector.map(ClaimRecord(_, Map.empty)))
  val openIdScope = scope(ScopeToken.OpenId)
  val profileScope = scope(ScopeToken("profile"), Claim("name"), Claim("given_name"), Claim("family_name"))
  val emailScope = scope(ScopeToken("email"), Claim("email"))

  val clientId1 = ClientId("test-client")

  /** A first-party client (no consent screen) registered for openid, profile and email. */
  val firstPartyClient = OAuthClientRecord(
    id = clientId1,
    tenantId = TenantId("default"),
    clientName = Map("en" -> "Test Client"),
    redirectUris = Set("https://example.com/callback"),
    scope = Set(ScopeToken.OpenId, ScopeToken("profile"), ScopeToken("email")),
    secret = None,
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    registrationFlow = None,
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
  )

  /** The same client, but one that shows the user a consent screen. */
  val promptingClient = firstPartyClient.copy(consentFlow = Some(ConsentFlow(allowPartial = true, rememberDuration = None)))

  final class Env:
    val userRepo = stub[UserRepository]
    val clientService = stub[OAuthConfigurationService]
    val service: UserInfoService = UserInfoService.Impl(userRepo, clientService)

  val spec = suite("UserInfoService")(
    test("getUserInfo only includes claims from granted scopes") {
      val env = Env()
      for
        _ <- env.userRepo.find.succeedsWith(Some(testUser))
        _ <- env.clientService.getScopes.succeedsWith(Vector(openIdScope, profileScope, emailScope))
        result <- env.service.getUserInfo(userId1, clientId1, Set(ScopeToken.OpenId, ScopeToken("profile")), None)
      yield assertTrue(
        result.claims.contains("sub"),
        result.claims.contains("name"),
        !result.claims.contains("email"),
      )
    },
    test("getUserInfo filters claims using requested_claims.userinfo") {
      val env = Env()
      val requestedClaims = RequestedClaims(
        userinfo = Map(Claim("name") -> ClaimRequest(Some(true), None, None)),
        idToken = Map.empty,
      )
      for
        _ <- env.userRepo.find.succeedsWith(Some(testUser))
        _ <- env.clientService.getScopes.succeedsWith(Vector(openIdScope, profileScope))
        _ <- env.clientService.find.succeedsWith(Some(promptingClient))
        result <- env.service.getUserInfo(userId1, clientId1, Set(ScopeToken.OpenId, ScopeToken("profile")), Some(requestedClaims))
      yield assertTrue(result.claims.contains("name"), !result.claims.contains("given_name"), !result.claims.contains("family_name"))
    },
    test("getUserInfo fails when user is missing") {
      val env = Env()
      for
        _ <- env.userRepo.find.succeedsWith(None)
        _ <- env.clientService.getScopes.succeedsWith(Vector(openIdScope))
        result <- env.service.getUserInfo(userId1, clientId1, Set(ScopeToken.OpenId), None).either
      yield assertTrue(result == Left(UserInfoError.InvalidToken))
    },
    // OIDC Core §5.4: with an access token issued, scope claims belong to UserInfo; the ID Token
    // carries only what `claims.id_token` asks of it.
    test("getUserInfoForIdToken carries no scope claims when the request asked for none") {
      val env = Env()
      for
        _ <- env.clientService.getScopes.succeedsWith(Vector(openIdScope, profileScope, emailScope))
        withoutClaims <- env.service.getUserInfoForIdToken(
          testUser,
          clientId1,
          Set(ScopeToken.OpenId, ScopeToken("profile"), ScopeToken("email")),
          None,
          None,
          None,
        )
        _ <- env.clientService.find.succeedsWith(Some(promptingClient))
        onlyUserinfoClaims <- env.service.getUserInfoForIdToken(
          testUser,
          clientId1,
          Set(ScopeToken.OpenId, ScopeToken("profile"), ScopeToken("email")),
          Some(RequestedClaims(userinfo = Map(Claim("email") -> ClaimRequest(Some(true), None, None)), idToken = Map.empty)),
          None,
          None,
        )
      yield assertTrue(
        withoutClaims.claims.keySet == Set("sub"),
        onlyUserinfoClaims.claims.keySet == Set("sub"),
      )
    },
    // `email` is a registered scope here, merely not among the granted ones: the intersection has
    // to be with what was granted, not with everything registered, or a client could pull a claim
    // its scopes never covered into the ID Token just by naming it.
    test("getUserInfoForIdToken does not release a registered claim the granted scopes do not cover to a client that prompts") {
      val env = Env()
      for
        _ <- env.clientService.getScopes.succeedsWith(Vector(openIdScope, profileScope, emailScope))
        _ <- env.clientService.find.succeedsWith(Some(promptingClient))
        result <- env.service.getUserInfoForIdToken(
          testUser,
          clientId1,
          Set(ScopeToken.OpenId, ScopeToken("profile")),
          Some(RequestedClaims(userinfo = Map.empty, idToken = Map(Claim("email") -> ClaimRequest(Some(true), None, None)))),
          None,
          None,
        )
      yield assertTrue(!result.claims.contains("email"))
    },
    test("getUserInfoForIdToken includes nonce and uses requested_claims.id_token") {
      val env = Env()
      val nonce = Nonce("test-nonce-123")
      val requestedClaims = RequestedClaims(
        userinfo = Map(Claim("email") -> ClaimRequest(Some(true), None, None)),
        idToken = Map(Claim("name") -> ClaimRequest(Some(true), None, None)),
      )
      for
        _ <- env.clientService.getScopes.succeedsWith(Vector(openIdScope, profileScope, emailScope))
        _ <- env.clientService.find.succeedsWith(Some(promptingClient))
        result <- env.service.getUserInfoForIdToken(
          testUser,
          clientId1,
          Set(ScopeToken.OpenId, ScopeToken("profile"), ScopeToken("email")),
          Some(requestedClaims),
          None,
          Some(nonce),
        )
      yield assertTrue(
        result.claims("nonce") == Json.Str(nonce.toString),
        result.claims.contains("sub"),
        result.claims.contains("name"),
        !result.claims.contains("email"),
      )
    },
    // A first-party client shows no consent screen, so what it asks for through `claims` is released from
    // any scope registered for it, not just the granted ones. (The conformance suite's
    // oidcc-claims-essential asks for `name` with scope=openid and warns when it is missing.)
    test("a first-party client gets a claim it names in claims.userinfo from a registered scope it was not granted") {
      val env = Env()
      val requested = RequestedClaims(userinfo = Map(Claim("name") -> ClaimRequest(Some(true), None, None)), idToken = Map.empty)
      for
        _ <- env.userRepo.find.succeedsWith(Some(testUser))
        _ <- env.clientService.getScopes.succeedsWith(Vector(openIdScope, profileScope, emailScope))
        _ <- env.clientService.find.succeedsWith(Some(firstPartyClient))
        result <- env.service.getUserInfo(userId1, clientId1, Set(ScopeToken.OpenId), Some(requested))
      yield assertTrue(result.claims.get("name").contains(Json.Str("John Doe")), !result.claims.contains("given_name"))
    },
    test("a first-party client still gets nothing from a scope that is not registered for it") {
      val env = Env()
      val requested = RequestedClaims(userinfo = Map(Claim("email") -> ClaimRequest(Some(true), None, None)), idToken = Map.empty)
      val withoutEmail = firstPartyClient.copy(scope = Set(ScopeToken.OpenId, ScopeToken("profile")))
      for
        _ <- env.userRepo.find.succeedsWith(Some(testUser))
        _ <- env.clientService.getScopes.succeedsWith(Vector(openIdScope, profileScope, emailScope))
        _ <- env.clientService.find.succeedsWith(Some(withoutEmail))
        result <- env.service.getUserInfo(userId1, clientId1, Set(ScopeToken.OpenId), Some(requested))
      yield assertTrue(!result.claims.contains("email"))
    },
    test("a client that prompts does not get a claim from a scope it was not granted, even by naming it") {
      val env = Env()
      val requested = RequestedClaims(userinfo = Map(Claim("name") -> ClaimRequest(Some(true), None, None)), idToken = Map.empty)
      for
        _ <- env.userRepo.find.succeedsWith(Some(testUser))
        _ <- env.clientService.getScopes.succeedsWith(Vector(openIdScope, profileScope, emailScope))
        _ <- env.clientService.find.succeedsWith(Some(promptingClient))
        result <- env.service.getUserInfo(userId1, clientId1, Set(ScopeToken.OpenId), Some(requested))
      yield assertTrue(!result.claims.contains("name"))
    },
    test("a first-party client gets a claim it names in claims.id_token from a registered scope it was not granted") {
      val env = Env()
      val requested = RequestedClaims(userinfo = Map.empty, idToken = Map(Claim("email") -> ClaimRequest(Some(true), None, None)))
      for
        _ <- env.clientService.getScopes.succeedsWith(Vector(openIdScope, profileScope, emailScope))
        _ <- env.clientService.find.succeedsWith(Some(firstPartyClient))
        result <- env.service.getUserInfoForIdToken(testUser, clientId1, Set(ScopeToken.OpenId), Some(requested), None, None)
      yield assertTrue(result.claims.get("email").contains(Json.Str("john@example.com")))
    },
    test("a first-party client's claims request does not widen what it gets when the request names none") {
      val env = Env()
      for
        _ <- env.userRepo.find.succeedsWith(Some(testUser))
        _ <- env.clientService.getScopes.succeedsWith(Vector(openIdScope, profileScope, emailScope))
        result <- env.service.getUserInfo(userId1, clientId1, Set(ScopeToken.OpenId, ScopeToken("profile")), None)
      yield assertTrue(result.claims.contains("name"), !result.claims.contains("email"))
    },
  )
