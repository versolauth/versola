package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.{Claim, ClientId, ScopeToken}
import versola.oauth.model.*
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.model.{ClaimRequest, RequestedClaims}
import versola.user.model.UserId
import versola.util.{DatabaseSpecBase, MAC}
import zio.*
import zio.http.URL
import zio.prelude.EqualOps
import zio.test.*

import java.util.UUID

trait AuthorizationCodeRepositorySpec extends DatabaseSpecBase[AuthorizationCodeRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val code1 = MAC(Array.fill(32)(1.toByte))

  val clientId1 = ClientId("client-1")
  val clientId2 = ClientId("client-2")

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("a077fb08-9935-4a6d-8643-bf97c073bf0f"))

  val redirectUri1 = URL.decode("https://example.com/callback").toOption.get
  val redirectUri2 = URL.decode("https://other.com/callback").toOption.get

  val scope1 = Set(ScopeToken("read"), ScopeToken("write"))
  val scope2 = Set(ScopeToken("admin"))

  val codeChallenge1 = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
  val codeChallenge2 = CodeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")

  val ttl = 5.minutes

  val sessionId1 = MAC(Array.fill(32)(3.toByte))

  val requestedClaims1 = RequestedClaims(
    userinfo = Map(
      Claim("email") -> ClaimRequest(Some(true), None, None),
      Claim("name") -> ClaimRequest(None, None, None),
    ),
    idToken = Map(
      "auth_time" -> ClaimRequest(Some(true), None, None),
    ),
  )

  val uiLocales1 = Vector("en-US", "fr-CA")
  val uiLocales2 = Vector("de-DE", "es-ES", "ja-JP")

  val record = AuthorizationCodeRecord(
    sessionId = sessionId1,
    clientId = clientId1,
    userId = userId1,
    redirectUri = redirectUri1,
    scope = scope1,
    codeChallenge = codeChallenge1,
    codeChallengeMethod = CodeChallengeMethod.S256,
    requestedClaims = None,
    uiLocales = None,
  )

  val recordWithClaims = AuthorizationCodeRecord(
    sessionId = sessionId1,
    clientId = clientId1,
    userId = userId1,
    redirectUri = redirectUri1,
    scope = scope1,
    codeChallenge = codeChallenge1,
    codeChallengeMethod = CodeChallengeMethod.S256,
    requestedClaims = Some(requestedClaims1),
    uiLocales = Some(uiLocales1),
  )

  def testCases(env: AuthorizationCodeRepositorySpec.Env): List[Spec[AuthorizationCodeRepositorySpec.Env & Scope, Any]] =
    List(
      test("create, find, and delete authorization code") {
        for
          _ <- env.repository.create(code1, record, ttl)
          found <- env.repository.find(code1)
          _ <- env.repository.delete(code1)
          foundAfterDelete <- env.repository.find(code1)
        yield assertTrue(
          found === Some(record),
          foundAfterDelete.isEmpty,
        )
      },
      test("find returns None for expired code") {
        for
          _ <- env.repository.create(code1, record, 0.seconds)
          _ <- TestClock.adjust(1.second)
          found <- env.repository.find(code1)
        yield assertTrue(found.isEmpty)
      },
      test("find returns None for non-existent code") {
        for
          found <- env.repository.find(code1)
        yield assertTrue(found.isEmpty)
      },
      test("persist and retrieve authorization code with requested_claims and ui_locales") {
        for
          _ <- env.repository.create(code1, recordWithClaims, ttl)
          found <- env.repository.find(code1)
          _ <- env.repository.delete(code1)
        yield assertTrue(
          found.isDefined,
          found.get.requestedClaims.isDefined,
          found.get.requestedClaims.get.userinfo.size == 2,
          found.get.requestedClaims.get.idToken.size == 1,
          found.get.uiLocales.isDefined,
          found.get.uiLocales.get == uiLocales1,
        )
      },
      test("persist and retrieve authorization code with only requested_claims") {
        val recordWithOnlyClaims = record.copy(requestedClaims = Some(requestedClaims1))
        for
          _ <- env.repository.create(code1, recordWithOnlyClaims, ttl)
          found <- env.repository.find(code1)
          _ <- env.repository.delete(code1)
        yield assertTrue(
          found.isDefined,
          found.get.requestedClaims.isDefined,
          found.get.uiLocales.isEmpty,
        )
      },
      test("persist and retrieve authorization code with only ui_locales") {
        val recordWithOnlyLocales = record.copy(uiLocales = Some(uiLocales2))
        for
          _ <- env.repository.create(code1, recordWithOnlyLocales, ttl)
          found <- env.repository.find(code1)
          _ <- env.repository.delete(code1)
        yield assertTrue(
          found.isDefined,
          found.get.requestedClaims.isEmpty,
          found.get.uiLocales.isDefined,
          found.get.uiLocales.get == uiLocales2,
        )
      },
    )

object AuthorizationCodeRepositorySpec:
  case class Env(repository: AuthorizationCodeRepository)

