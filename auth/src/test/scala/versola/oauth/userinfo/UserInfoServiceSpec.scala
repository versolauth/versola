package versola.oauth.userinfo

import org.scalamock.stubs.ZIOStubs
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.{Claim, Scope, ScopeDescription, ScopeToken}
import versola.oauth.userinfo.model.{RequestedClaims, UserInfoError, UserInfoResponse}
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

  val userClaims = Json.Obj(
    "name" -> Json.Str("John Doe"),
    "given_name" -> Json.Str("John"),
    "family_name" -> Json.Str("Doe"),
  )

  val testUser = UserRecord(
    id = userId1,
    email = Some(email1),
    phone = None,
    claims = userClaims,
  )

  val profileScope = Scope(
    claims = Set(Claim("name"), Claim("given_name"), Claim("family_name")),
    description = ScopeDescription("Profile information"),
  )

  val emailScope = Scope(
    claims = Set(Claim("email")),
    description = ScopeDescription("Email address"),
  )

  class Env:
    val userRepo = stub[UserRepository]
    val clientService = stub[OAuthClientService]
    val service = UserInfoService.Impl(userRepo, clientService)

  def spec = suite("UserInfoService")(
    suite("getUserInfo")(
      test("successfully return user info with openid and profile scopes") {
        val env = Env()
        for
          _ <- env.userRepo.find.succeedsWith(Some(testUser))
          _ <- env.clientService.getAllScopesCached.succeedsWith(
            Map(
              ScopeToken.OpenId -> Scope(Set.empty, ScopeDescription("OpenID")),
              ScopeToken("profile") -> profileScope,
            )
          )

          result <- env.service.getUserInfo(
            userId = userId1,
            scope = Set(ScopeToken.OpenId, ScopeToken("profile")),
            requestedClaims = None,
            tokenUiLocales = None,
          )
        yield assertTrue(
          result.claims.contains("sub"),
          result.claims.get("sub").contains(Json.Str(userId1.toString)),
          result.claims.contains("name"),
          result.claims.get("name").contains(Json.Str("John Doe")),
          result.claims.contains("given_name"),
          result.claims.contains("family_name"),
        )
      },
      test("successfully return email claim with email scope") {
        val env = Env()
        for
          _ <- env.userRepo.find.succeedsWith(Some(testUser))
          _ <- env.clientService.getAllScopesCached.succeedsWith(
            Map(
              ScopeToken.OpenId -> Scope(Set.empty, ScopeDescription("OpenID")),
              ScopeToken("email") -> emailScope,
            )
          )

          result <- env.service.getUserInfo(
            userId = userId1,
            scope = Set(ScopeToken.OpenId, ScopeToken("email")),
            requestedClaims = None,
            tokenUiLocales = None,
          )
        yield assertTrue(
          result.claims.contains("email"),
          result.claims.get("email").contains(Json.Str(email1)),
        )
      },
      test("filter claims based on requested_claims parameter") {
        val env = Env()
        val requestedClaims = RequestedClaims(
          userinfo = Map(
            Claim("name") -> versola.oauth.userinfo.model.ClaimRequest(Some(true), None, None),
          ),
          idToken = Map.empty,
        )
        for
          _ <- env.userRepo.find.succeedsWith(Some(testUser))
          _ <- env.clientService.getAllScopesCached.succeedsWith(
            Map(
              ScopeToken.OpenId -> Scope(Set.empty, ScopeDescription("OpenID")),
              ScopeToken("profile") -> profileScope,
            )
          )

          result <- env.service.getUserInfo(
            userId = userId1,
            scope = Set(ScopeToken.OpenId, ScopeToken("profile")),
            requestedClaims = Some(requestedClaims),
            tokenUiLocales = None,
          )
        yield assertTrue(
          result.claims.contains("name"),
          !result.claims.contains("given_name"), // Not requested
          !result.claims.contains("family_name"), // Not requested
        )
      },
      test("fail with InsufficientScope when openid scope is missing") {
        val env = Env()
        for
          result <- env.service.getUserInfo(
            userId = userId1,
            scope = Set(ScopeToken("profile")), // Missing openid
            requestedClaims = None,
            tokenUiLocales = None,
          ).either
        yield assertTrue(
          result == Left(UserInfoError.InsufficientScope),
        )
      },
      test("fail with InvalidToken when user not found") {
        val env = Env()
        for
          _ <- env.userRepo.find.succeedsWith(None)
          _ <- env.clientService.getAllScopesCached.succeedsWith(
            Map(ScopeToken.OpenId -> Scope(Set.empty, ScopeDescription("OpenID")))
          )

          result <- env.service.getUserInfo(
            userId = userId1,
            scope = Set(ScopeToken.OpenId),
            requestedClaims = None,
            tokenUiLocales = None,
          ).either
        yield assertTrue(
          result == Left(UserInfoError.InvalidToken),
        )
      },
    ),
  )

