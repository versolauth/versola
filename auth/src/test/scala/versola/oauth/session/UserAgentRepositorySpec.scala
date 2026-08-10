package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.model.UserAgentData
import versola.oauth.session.model.{UserAgentDetails, UserAgentId}
import versola.user.model.UserId
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.util.UUID

trait UserAgentRepositorySpec extends DatabaseSpecBase[UserAgentRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("a077fb08-9935-4a6d-8643-bf97c073bf0f"))

  val userAgentId1 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))
  val userAgentId2 = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000c2"))

  val details1 = UserAgentDetails(platform = Some("desktop"), os = Some("macOS 14"), browser = Some("Chrome"), version = Some("120"))
  val details2 = UserAgentDetails(platform = Some("ios"), os = Some("iOS 17"), browser = Some("Safari"), version = Some("17"))

  val data1 = UserAgentData(userAgent = Some("ua-1"), userId = userId1, details = details1)
  val data2 = UserAgentData(userAgent = Some("ua-2"), userId = userId2, details = details2)

  val ttl = 30.days

  def testCases(env: UserAgentRepositorySpec.Env): List[Spec[UserAgentRepositorySpec.Env & Scope, Any]] =
    List(
      test("create and find a user agent") {
        for
          _     <- env.repository.create(userAgentId1, data1, ttl)
          found <- env.repository.find(userAgentId1)
        yield assertTrue(found.contains(details1))
      },
      test("find returns None for a non-existent user agent") {
        for found <- env.repository.find(userAgentId1)
        yield assertTrue(found.isEmpty)
      },
      test("find returns None for an expired user agent") {
        for
          _     <- env.repository.create(userAgentId1, data1, 0.seconds)
          _     <- TestClock.adjust(1.second)
          found <- env.repository.find(userAgentId1)
        yield assertTrue(found.isEmpty)
      },
      test("create is idempotent when the id already exists") {
        for
          _     <- env.repository.create(userAgentId1, data1, ttl)
          _     <- env.repository.create(userAgentId1, data2, ttl)
          found <- env.repository.find(userAgentId1)
        yield assertTrue(found.contains(details1))
      },
      test("findMany returns details for known, non-expired ids only") {
        for
          _     <- env.repository.create(userAgentId1, data1, ttl)
          _     <- env.repository.create(userAgentId2, data2, 0.seconds)
          _     <- TestClock.adjust(1.second)
          found <- env.repository.findMany(List(userAgentId1, userAgentId2))
        yield assertTrue(found == Map(userAgentId1 -> details1))
      },
      test("findMany returns an empty map for an empty id list") {
        for found <- env.repository.findMany(List.empty)
        yield assertTrue(found.isEmpty)
      },
      test("findMany returns an empty map when no ids are known") {
        for found <- env.repository.findMany(List(userAgentId1, userAgentId2))
        yield assertTrue(found.isEmpty)
      },
      test("touch slides the expiry forward and refreshes the details") {
        for
          _       <- env.repository.create(userAgentId1, data1, 1.hour)
          _       <- TestClock.adjust(30.minutes)
          updated <- env.repository.touch(userAgentId1, data1.copy(details = details2), 1.hour)
          _       <- TestClock.adjust(45.minutes)
          found   <- env.repository.find(userAgentId1)
        yield assertTrue(updated, found.contains(details2))
      },
      test("touch returns false and is a no-op for a non-existent id") {
        for
          updated <- env.repository.touch(userAgentId1, data1, ttl)
          found   <- env.repository.find(userAgentId1)
        yield assertTrue(!updated, found.isEmpty)
      },
      test("touch still finds and refreshes a row that expired but was not yet cleaned up") {
        // Expiry itself is only enforced by find/findMany and by the periodic cleanup job that
        // deletes stale rows; touch has no expiry filter, so a row that's merely past its
        // expires_at (but not yet physically removed) is still touchable.
        for
          _       <- env.repository.create(userAgentId1, data1, 0.seconds)
          _       <- TestClock.adjust(1.second)
          updated <- env.repository.touch(userAgentId1, data1.copy(details = details2), ttl)
          found   <- env.repository.find(userAgentId1)
        yield assertTrue(updated, found.contains(details2))
      },
    )

object UserAgentRepositorySpec:
  case class Env(repository: UserAgentRepository)
