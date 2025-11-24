package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.model.*
import versola.oauth.token.AuthorizationCodeRepository
import versola.security.MAC
import versola.user.model.UserId
import versola.util.DatabaseSpecBase
import zio.*
import zio.http.URL
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

  val record = AuthorizationCodeRecord(
    clientId = clientId1,
    userId = userId1,
    redirectUri = redirectUri1,
    scope = scope1,
    codeChallenge = codeChallenge1,
    codeChallengeMethod = CodeChallengeMethod.S256,
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
          found.contains(record),
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
    )

object AuthorizationCodeRepositorySpec:
  case class Env(repository: AuthorizationCodeRepository)

