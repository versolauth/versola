package versola.oauth.userinfo

import org.scalamock.stubs.ZIOStubs
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{Claim, ClaimRecord, ScopeRecord, ScopeToken}
import versola.oauth.model.Nonce
import versola.oauth.userinfo.model.{ClaimRequest, RequestedClaims, UserInfoError}
import versola.user.UserRepository
import versola.user.model.{UserId, UserRecord}
import versola.util.{Email, UnitSpecBase}
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
  )

  private def scope(id: ScopeToken, claims: Claim*) = ScopeRecord(id, claims.toVector.map(ClaimRecord(_)))
  val openIdScope = scope(ScopeToken.OpenId)
  val profileScope = scope(ScopeToken("profile"), Claim("name"), Claim("given_name"), Claim("family_name"))
  val emailScope = scope(ScopeToken("email"), Claim("email"))

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
        result <- env.service.getUserInfo(userId1, Set(ScopeToken.OpenId, ScopeToken("profile")), None, None)
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
        result <- env.service.getUserInfo(userId1, Set(ScopeToken.OpenId, ScopeToken("profile")), Some(requestedClaims), None)
      yield assertTrue(result.claims.contains("name"), !result.claims.contains("given_name"), !result.claims.contains("family_name"))
    },
    test("getUserInfo fails when user is missing") {
      val env = Env()
      for
        _ <- env.userRepo.find.succeedsWith(None)
        _ <- env.clientService.getScopes.succeedsWith(Vector(openIdScope))
        result <- env.service.getUserInfo(userId1, Set(ScopeToken.OpenId), None, None).either
      yield assertTrue(result == Left(UserInfoError.InvalidToken))
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
        result <- env.service.getUserInfoForIdToken(
          testUser,
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
  )
