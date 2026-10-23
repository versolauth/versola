package versola.oauth.challenge.passkey

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.PasskeyEnrollmentState
import versola.user.model.UserId
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

trait PasskeyEnrollmentRepositorySpec extends DatabaseSpecBase[PasskeyEnrollmentRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("a077fb08-9935-4a6d-8643-bf97c073bf0f"))

  val t0 = Instant.parse("2026-01-01T00:00:00Z")
  val t1 = Instant.parse("2026-01-05T00:00:00Z")

  def testCases(env: PasskeyEnrollmentRepositorySpec.Env): List[Spec[PasskeyEnrollmentRepositorySpec.Env & Scope, Any]] =
    List(
      test("find returns None for a user never offered a passkey") {
        for found <- env.repository.find(userId1)
        yield assertTrue(found.isEmpty)
      },
      test("recordOffer stores the first offer with no skips") {
        for
          _ <- env.repository.recordOffer(userId1, t0)
          found <- env.repository.find(userId1)
        yield assertTrue(found == Some(PasskeyEnrollmentState(userId = userId1, firstOfferedAt = t0, skips = 0)))
      },
      test("a later recordOffer keeps the first time") {
        for
          _ <- env.repository.recordOffer(userId1, t0)
          _ <- env.repository.recordOffer(userId1, t1)
          found <- env.repository.find(userId1)
        yield assertTrue(found.map(_.firstOfferedAt) == Some(t0))
      },
      test("recordSkip counts every skip and keeps the first offer time") {
        for
          _ <- env.repository.recordOffer(userId1, t0)
          _ <- env.repository.recordSkip(userId1, t1)
          _ <- env.repository.recordSkip(userId1, t1)
          found <- env.repository.find(userId1)
        yield assertTrue(found == Some(PasskeyEnrollmentState(userId = userId1, firstOfferedAt = t0, skips = 2)))
      },
      test("recordSkip for a user without a record starts the count at one") {
        for
          _ <- env.repository.recordSkip(userId1, t1)
          found <- env.repository.find(userId1)
        yield assertTrue(found == Some(PasskeyEnrollmentState(userId = userId1, firstOfferedAt = t1, skips = 1)))
      },
      test("concurrent skips are all counted") {
        for
          _ <- env.repository.recordOffer(userId1, t0)
          _ <- ZIO.foreachParDiscard(1 to 10)(_ => env.repository.recordSkip(userId1, t1))
          found <- env.repository.find(userId1)
        yield assertTrue(found.map(_.skips) == Some(10))
      },
      test("one user's skips do not touch another's") {
        for
          _ <- env.repository.recordSkip(userId1, t1)
          other <- env.repository.find(userId2)
        yield assertTrue(other.isEmpty)
      },
    )

object PasskeyEnrollmentRepositorySpec:
  case class Env(repository: PasskeyEnrollmentRepository)
