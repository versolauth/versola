package versola.central.antifraud

import versola.central.users.{AuthClient, UserId}
import versola.util.{Patch, UnitSpecBase}
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object AntifraudSettingsServiceSpec extends UnitSpecBase:
  private val admin = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000f1"))
  private val now = Instant.parse("2026-11-20T10:00:00Z")

  private val limit = VelocityLimitDto(perMinute = 0, perTenMinutes = 0, perHour = 3, perDay = 0, weight = 50)
  private val tuned = AntifraudSettings(
    mode = AntifraudMode.enforce,
    challengeAt = 0,
    blockAt = 80,
    pow = PowDifficultyDto(14, 18, 15),
    thresholds = VelocityThresholdsDto(limit, limit, limit, limit, limit, 40),
    smsLimits = SmsLimitsDto(SmsLimitDto(10, 0, 0.1), Map("49" -> SmsLimitDto(2, 5, 0.2))),
    ruleWeights = Map("velocity_phone_1h" -> 40),
    shadowRules = Set("velocity_ip_1m"),
  )
  private val off = tuned.copy(mode = AntifraudMode.shadow, blockAt = 0, ruleWeights = Map.empty, shadowRules = Set.empty)
  private val patch = AntifraudSettingsPatch.empty.copy(blockAt = Some(Patch.Modified(90)))
  private val patched = tuned.copy(blockAt = 90)

  class Env:
    val repository = stub[AntifraudSettingsRepository]
    val lists = stub[AntifraudListRepository]
    val auth = stub[AuthClient]
    val service = AntifraudSettingsService.Impl(repository, lists, auth)

  private def patchRequest(version: Option[Long]) = PatchAntifraudSettingsRequest(patch = patch, userId = admin, version = version)

  def spec = suite("AntifraudSettingsService")(
    suite("get")(
      test("answers the stored settings and their version, without asking auth") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(Some(VersionedAntifraudSettings(tuned, 4)))
          got <- env.service.get
        yield assertTrue(got == VersionedAntifraudSettings(tuned, 4), env.auth.checkAntifraudSettings.calls.isEmpty)
      },
      test("where nothing is stored answers what auth says is everything off, at version 0") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(None)
          _ <- env.auth.checkAntifraudSettings.succeedsWith(off)
          got <- env.service.get
        yield assertTrue(
          got == VersionedAntifraudSettings(off, 0),
          env.auth.checkAntifraudSettings.calls == List(SettingsCheck(settings = None, patch = None)),
        )
      },
    ),
    suite("put")(
      test("is validated by auth, which answers the settings to store, and written as the admin on the version named") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          _ <- env.auth.checkAntifraudSettings.succeedsWith(tuned.copy(blockAt = 81))
          _ <- env.repository.put.succeedsWith(Some(4L))
          put <- env.service.put(PutAntifraudSettingsRequest(settings = tuned, userId = admin, version = Some(3)))
        yield assertTrue(
          put == VersionedAntifraudSettings(tuned.copy(blockAt = 81), 4),
          env.auth.checkAntifraudSettings.calls == List(SettingsCheck(settings = Some(tuned), patch = None)),
          env.repository.put.calls == List((tuned.copy(blockAt = 81), admin.toString, now, Some(3L))),
        )
      },
      test("without a version replaces whatever is there") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          _ <- env.auth.checkAntifraudSettings.succeedsWith(tuned)
          _ <- env.repository.put.succeedsWith(Some(9L))
          _ <- env.service.put(PutAntifraudSettingsRequest(settings = tuned, userId = admin, version = None))
        yield assertTrue(env.repository.put.calls.map(_._4) == List(None))
      },
      test("auth's refusal comes through and nothing is written") {
        val env = Env()
        for
          _ <- env.auth.checkAntifraudSettings.failsWith(AntifraudListError.Invalid("blockAt is out of range"))
          failed <- env.service.put(PutAntifraudSettingsRequest(settings = tuned, userId = admin, version = None)).either
        yield assertTrue(failed == Left(AntifraudListError.Invalid("blockAt is out of range")), env.repository.put.calls.isEmpty)
      },
      test("on a version the settings have left is a stale version, with the version they are at") {
        val env = Env()
        for
          _ <- env.auth.checkAntifraudSettings.succeedsWith(tuned)
          _ <- env.repository.put.succeedsWith(None)
          _ <- env.repository.find.succeedsWith(Some(VersionedAntifraudSettings(off, 5)))
          failed <- env.service.put(PutAntifraudSettingsRequest(settings = tuned, userId = admin, version = Some(3))).either
        yield assertTrue(failed == Left(AntifraudListError.StaleVersion("the settings are at version 5", Some(5))))
      },
      test("on a version where nothing is stored (0 meaning none) names version 0 as the current one when it is still not there") {
        val env = Env()
        for
          _ <- env.auth.checkAntifraudSettings.succeedsWith(tuned)
          _ <- env.repository.put.succeedsWith(None)
          _ <- env.repository.find.succeedsWith(None)
          failed <- env.service.put(PutAntifraudSettingsRequest(settings = tuned, userId = admin, version = Some(2))).either
        yield assertTrue(failed == Left(AntifraudListError.StaleVersion("the settings are at version 0", Some(0))))
      },
    ),
    suite("patch")(
      test("is made by auth on the stored settings and written on the version it was made from") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          _ <- env.repository.find.succeedsWith(Some(VersionedAntifraudSettings(tuned, 3)))
          _ <- env.auth.checkAntifraudSettings.succeedsWith(patched)
          _ <- env.repository.put.succeedsWith(Some(4L))
          result <- env.service.patch(patchRequest(Some(3)))
        yield assertTrue(
          result == VersionedAntifraudSettings(patched, 4),
          env.auth.checkAntifraudSettings.calls == List(SettingsCheck(settings = Some(tuned), patch = Some(patch))),
          env.repository.put.calls == List((patched, admin.toString, now, Some(3L))),
        )
      },
      test("where nothing is stored is made on none, and written on version 0") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(None)
          _ <- env.auth.checkAntifraudSettings.succeedsWith(patched)
          _ <- env.repository.put.succeedsWith(Some(1L))
          result <- env.service.patch(patchRequest(None))
        yield assertTrue(
          result == VersionedAntifraudSettings(patched, 1),
          env.auth.checkAntifraudSettings.calls == List(SettingsCheck(settings = None, patch = Some(patch))),
          env.repository.put.calls.map(_._4) == List(Some(0L)),
        )
      },
      test("on a version the settings have left is refused before auth is asked, with the version they are at") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(Some(VersionedAntifraudSettings(tuned, 5)))
          failed <- env.service.patch(patchRequest(Some(3))).either
        yield assertTrue(
          failed == Left(AntifraudListError.StaleVersion("the settings are at version 5", Some(5))),
          env.auth.checkAntifraudSettings.calls.isEmpty,
          env.repository.put.calls.isEmpty,
        )
      },
      test("on a named version that is changed between the read and the write is not made again") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(Some(VersionedAntifraudSettings(tuned, 3)))
          _ <- env.auth.checkAntifraudSettings.succeedsWith(patched)
          _ <- env.repository.put.succeedsWith(None)
          failed <- env.service.patch(patchRequest(Some(3))).either
        yield assertTrue(
          failed.left.exists(_.isInstanceOf[AntifraudListError.StaleVersion]),
          env.repository.put.calls.size == 1,
          env.auth.checkAntifraudSettings.calls.size == 1,
        )
      },
      test("without a version, one changed between the read and the write is made again on what was written, once") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          reads <- Ref.make(List(Some(VersionedAntifraudSettings(tuned, 3)), Some(VersionedAntifraudSettings(tuned.copy(challengeAt = 7), 4))))
          _ <- env.repository.find.returnsZIO(_ => reads.modify(r => (r.head, if r.tail.isEmpty then r else r.tail)))
          writes <- Ref.make(List(Option.empty[Long], Some(5L)))
          _ <- env.repository.put.returnsZIO((_, _, _, _) => writes.modify(w => (w.head, w.tail)))
          _ <- env.auth.checkAntifraudSettings.returnsZIO(check => ZIO.succeed(check.settings.get.copy(blockAt = 90)))
          result <- env.service.patch(patchRequest(None))
        yield assertTrue(
          result == VersionedAntifraudSettings(tuned.copy(challengeAt = 7, blockAt = 90), 5),
          env.repository.put.calls.map(_._4) == List(Some(3L), Some(4L)),
          env.auth.checkAntifraudSettings.calls.map(_.settings.map(_.challengeAt)) == List(Some(0), Some(7)),
        )
      },
      test("without a version, a second change in between is a stale version: it is retried once and not again") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(Some(VersionedAntifraudSettings(tuned, 3)))
          _ <- env.auth.checkAntifraudSettings.succeedsWith(patched)
          _ <- env.repository.put.succeedsWith(None)
          failed <- env.service.patch(patchRequest(None)).either
        yield assertTrue(
          failed.left.exists(_.isInstanceOf[AntifraudListError.StaleVersion]),
          env.repository.put.calls.size == 2,
        )
      },
      test("auth's refusal of the result comes through and nothing is written") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(Some(VersionedAntifraudSettings(tuned, 3)))
          _ <- env.auth.checkAntifraudSettings.failsWith(AntifraudListError.Invalid("blockAt is below challengeAt"))
          failed <- env.service.patch(patchRequest(None)).either
        yield assertTrue(failed == Left(AntifraudListError.Invalid("blockAt is below challengeAt")), env.repository.put.calls.isEmpty)
      },
    ),
    suite("reset")(
      test("writes what auth says is everything off, as the admin, and moves the version") {
        val env = Env()
        for
          _ <- TestClock.setTime(now)
          _ <- env.auth.checkAntifraudSettings.succeedsWith(off)
          _ <- env.repository.put.succeedsWith(Some(6L))
          _ <- env.service.reset(DeleteAntifraudSettingsRequest(admin), None)
          _ <- env.service.reset(DeleteAntifraudSettingsRequest(admin), Some(6))
        yield assertTrue(
          env.auth.checkAntifraudSettings.calls.forall(_ == SettingsCheck(settings = None, patch = None)),
          env.repository.put.calls == List((off, admin.toString, now, None), (off, admin.toString, now, Some(6L))),
        )
      },
      test("on a version the settings have left is a stale version and writes nothing") {
        val env = Env()
        for
          _ <- env.auth.checkAntifraudSettings.succeedsWith(off)
          _ <- env.repository.put.succeedsWith(None)
          _ <- env.repository.find.succeedsWith(Some(VersionedAntifraudSettings(tuned, 8)))
          failed <- env.service.reset(DeleteAntifraudSettingsRequest(admin), Some(6)).either
        yield assertTrue(failed == Left(AntifraudListError.StaleVersion("the settings are at version 8", Some(8))))
      },
    ),
    suite("state")(
      test("is the settings with their version and the version of the lists") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(Some(VersionedAntifraudSettings(tuned, 4)))
          _ <- env.lists.version.succeedsWith(11L)
          state <- env.service.state
        yield assertTrue(state == PolicyState(settingsVersion = 4, settings = Some(tuned), listsVersion = 11))
      },
      test("with no stored settings is version 0 and no settings, which needs no answer from auth") {
        val env = Env()
        for
          _ <- env.repository.find.succeedsWith(None)
          _ <- env.lists.version.succeedsWith(0L)
          state <- env.service.state
        yield assertTrue(
          state == PolicyState(settingsVersion = 0, settings = None, listsVersion = 0),
          env.auth.checkAntifraudSettings.calls.isEmpty,
        )
      },
    ),
  )
