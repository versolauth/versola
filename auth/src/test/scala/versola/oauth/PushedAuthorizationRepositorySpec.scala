package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.authorize.PushedAuthorizationRepository
import versola.oauth.authorize.model.PushedAuthorizationRecord
import versola.oauth.client.model.ClientId
import versola.util.{DatabaseSpecBase, MAC}
import zio.*
import zio.prelude.EqualOps
import zio.test.*

trait PushedAuthorizationRepositorySpec extends DatabaseSpecBase[PushedAuthorizationRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val requestUri1 = MAC(Array.fill(32)(1.toByte))
  val requestUri2 = MAC(Array.fill(32)(2.toByte))

  val ttl = 60.seconds

  val record = PushedAuthorizationRecord(
    clientId = ClientId("client-1"),
    params = Map(
      "client_id" -> List("client-1"),
      "response_type" -> List("code"),
      "redirect_uri" -> List("https://example.com/callback"),
      "scope" -> List("openid profile"),
      "resource" -> List("https://api.example.com", "https://other.example.com"),
    ),
  )

  def testCases(env: PushedAuthorizationRepositorySpec.Env): List[Spec[PushedAuthorizationRepositorySpec.Env & Scope, Any]] =
    List(
      test("create and consume a pushed authorization request") {
        for
          _ <- env.repository.create(requestUri1, record, ttl)
          found <- env.repository.consume(requestUri1)
        yield assertTrue(found === Some(record))
      },
      test("consume returns None on the second use") {
        for
          _ <- env.repository.create(requestUri1, record, ttl)
          first <- env.repository.consume(requestUri1)
          second <- env.repository.consume(requestUri1)
        yield assertTrue(first.isDefined, second.isEmpty)
      },
      test("consume returns None for an expired request") {
        for
          _ <- env.repository.create(requestUri1, record, 0.seconds)
          _ <- TestClock.adjust(1.second)
          found <- env.repository.consume(requestUri1)
        yield assertTrue(found.isEmpty)
      },
      test("consume returns None for an unknown request_uri") {
        for
          found <- env.repository.consume(requestUri2)
        yield assertTrue(found.isEmpty)
      },
      test("concurrent consume attempts - only one should succeed") {
        for
          _ <- env.repository.create(requestUri1, record, ttl)
          results <- ZIO.collectAllPar(List.fill(10)(env.repository.consume(requestUri1)))
        yield assertTrue(results.count(_.isDefined) == 1)
      },
    )

object PushedAuthorizationRepositorySpec:
  case class Env(repository: PushedAuthorizationRepository)
