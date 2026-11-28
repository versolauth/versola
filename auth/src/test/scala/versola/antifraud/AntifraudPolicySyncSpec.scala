package versola.antifraud

import versola.auth.TestEnvConfig
import versola.oauth.client.CentralSyncTokenService
import versola.util.UnitSpecBase
import zio.*
import zio.http.{Request, Response, Status}
import zio.json.*
import zio.metrics.Metric
import zio.test.*

import java.time.Instant
import java.util.UUID

object AntifraudPolicySyncSpec extends UnitSpecBase:

  private val tuning = Tuning.off.copy(blockAt = 80, shadowRules = Set("velocity_ip_1m"))

  private val entry = PolicyListEntry(
    id = UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
    listType = "deny",
    valueType = "prefix",
    value = "4915",
    tenantId = Some("tenant-a"),
    expiresAt = None,
    reason = Some("sms pumping"),
    source = "manual",
    shadow = false,
    createdBy = "alice",
    createdAt = Instant.parse("2026-11-01T00:00:00Z"),
  )

  private class Env:
    val source = stub[AntifraudPolicySource]
    val settings = stub[AntifraudSettingsRepository]
    val lists = stub[AntifraudListRepository]
    val sync = AntifraudPolicySync.Impl(source, settings, lists)

    /** The copy is at the given versions: the settings row (none at all for `None`) and the lists, which were last taken
      * whole at `fullAt`: the time the test clock starts at, so a whole copy is not due unless the case says it is. */
    def copyAt(settingsVersion: Option[Long], listsVersion: Long, fullAt: Option[Instant] = Some(Instant.EPOCH)) =
      settings.find.succeedsWith(settingsVersion.map(StoredTuning(tuning, _))) *>
        lists.copied.succeedsWith(AntifraudListRepository.Copy(listsVersion, fullAt)) *>
        settings.replace.succeedsWith(()) *>
        lists.replaceAll.succeedsWith(true) *>
        lists.applyChanges.succeedsWith(true)

    def writes: Boolean =
      settings.replace.calls.nonEmpty || lists.replaceAll.calls.nonEmpty || lists.applyChanges.calls.nonEmpty

    /** What a whole copy was given: the entries and the version. */
    def whole = lists.replaceAll.calls.map(c => (c._1, c._2))

  def spec = suite("AntifraudPolicySync")(
    test("writes nothing when the copy is at the versions central has") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 7))
        _ <- env.sync.sync
      yield assertTrue(!env.writes, env.source.lists.calls.isEmpty)
    },
    test("replaces the settings with central's when their version differs, and does not read the lists when theirs is the same") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7, fullAt = Some(Instant.parse("2026-11-20T09:00:00Z")))
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 5, settings = Some(tuning), listsVersion = 7))
        _ <- TestClock.setTime(Instant.parse("2026-11-20T10:00:00Z"))
        _ <- env.sync.sync
      yield assertTrue(
        env.settings.replace.calls == List((Some(tuning), 5L, Instant.parse("2026-11-20T10:00:00Z"))),
        env.lists.replaceAll.calls.isEmpty,
        env.source.lists.calls.isEmpty,
      )
    },
    test("a copy with no settings row is replaced when central has settings") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = None, listsVersion = 7)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 1, settings = Some(tuning), listsVersion = 7))
        _ <- env.sync.sync
      yield assertTrue(env.settings.replace.calls.map(c => (c._1, c._2)) == List((Some(tuning), 1L)))
    },
    test("central having no settings removes the copy: replace(None, 0)") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 0, settings = None, listsVersion = 7))
        _ <- env.sync.sync
      yield assertTrue(env.settings.replace.calls.map(c => (c._1, c._2)) == List((None, 0L)))
    },
    test("central having no settings and the copy having none either writes nothing") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = None, listsVersion = 7)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 0, settings = None, listsVersion = 7))
        _ <- env.sync.sync
      yield assertTrue(env.settings.replace.calls.isEmpty)
    },
    test("fetches the lists only when their version differs, and stores them under the version of the response") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 8))
        // central's lists moved on again between the two calls
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 9, full = true, entries = List(entry), removed = Nil))
        _ <- env.sync.sync
      yield assertTrue(
        env.source.lists.calls.size == 1,
        env.whole == List((List(entry), 9L)),
        env.settings.replace.calls.isEmpty,
      )
    },
    test("settings and lists that both differ are both written") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(1), listsVersion = 1)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 2, settings = Some(tuning), listsVersion = 2))
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 2, full = true, entries = Nil, removed = Nil))
        _ <- env.sync.sync
      yield assertTrue(env.settings.replace.calls.map(_._2) == List(2L), env.whole == List((Nil, 2L)))
    },
    test("a source that fails does not fail sync and leaves the copy; the next round that works writes") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.source.state.failsWith(RuntimeException("central is down"))
        down <- env.sync.sync.exit
        wroteWhileDown = env.writes
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 5, settings = Some(tuning), listsVersion = 8))
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 8, full = true, entries = List(entry), removed = Nil))
        _ <- env.sync.sync
      yield assertTrue(
        down.isSuccess,
        !wroteWhileDown,
        env.settings.replace.calls.map(_._2) == List(5L),
        env.whole == List((List(entry), 8L)),
      )
    },
    // versolauth/versola#1052: the lists are copied by delta.
    test("asks for the lists with the version of the copy") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 9))
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 9, full = false, entries = Nil, removed = Nil))
        _ <- env.sync.sync
      yield assertTrue(env.source.lists.calls == List(Some(7L)))
    },
    test("applies a delta from the version of the copy to the version it was read at, and takes nothing whole") {
      val env = Env()
      val gone = UUID.fromString("00000000-0000-0000-0000-0000000000e2")
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 8))
        // central's lists moved on again between the two calls
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 9, full = false, entries = List(entry), removed = List(gone)))
        _ <- env.sync.sync
      yield assertTrue(
        env.lists.applyChanges.calls == List((List(entry), List(gone), 7L, 9L)),
        env.lists.replaceAll.calls.isEmpty,
      )
    },
    test("a delta that another instance applied first is not an error and not a second write") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.lists.applyChanges.succeedsWith(false)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 8))
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 8, full = false, entries = List(entry), removed = Nil))
        failed <- env.sync.sync.exit
      yield assertTrue(failed.isSuccess, env.lists.applyChanges.calls.size == 1, env.lists.replaceAll.calls.isEmpty)
    },
    test("takes the whole list when central says it is whole: a copy that missed versions, or is ahead of central") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 70)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 3))
        _ <- TestClock.setTime(Instant.parse("2026-11-20T10:00:00Z"))
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 3, full = true, entries = List(entry), removed = Nil))
        _ <- env.sync.sync
      yield assertTrue(
        env.source.lists.calls == List(None),
        env.lists.replaceAll.calls.map(c => (c._1, c._2)) == List((List(entry), 3L)),
        env.lists.applyChanges.calls.isEmpty,
      )
    },
    test("takes the whole list when FullCopyInterval has passed since the last whole copy, though the versions agree") {
      val env = Env()
      val now = Instant.parse("2026-11-20T10:00:00Z")
      val taken = now.minusSeconds(AntifraudPolicySync.FullCopyInterval.toSeconds)
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7, fullAt = Some(taken))
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 7))
        _ <- TestClock.setTime(now)
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 7, full = true, entries = List(entry), removed = Nil))
        _ <- env.sync.sync
      yield assertTrue(
        // Asked without a version, so that central answers the whole list and not a delta.
        env.source.lists.calls == List(None),
        env.lists.replaceAll.calls == List((List(entry), 7L, now, now.minusSeconds(AntifraudPolicySync.FullCopyInterval.toSeconds))),
      )
    },
    test("takes the whole list when the versions differ and the whole copy is due, not the delta") {
      val env = Env()
      val now = Instant.parse("2026-11-20T10:00:00Z")
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7, fullAt = Some(now.minusSeconds(7 * 3600)))
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 8))
        _ <- TestClock.setTime(now)
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 8, full = true, entries = List(entry), removed = Nil))
        _ <- env.sync.sync
      yield assertTrue(env.source.lists.calls == List(None), env.lists.replaceAll.calls.size == 1, env.lists.applyChanges.calls.isEmpty)
    },
    test("a whole copy that was never taken is due: the lists are asked for whole") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7, fullAt = None)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 7))
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 7, full = true, entries = List(entry), removed = Nil))
        _ <- env.sync.sync
      yield assertTrue(env.source.lists.calls == List(None), env.lists.replaceAll.calls.size == 1)
    },
    test("a whole copy one second before it is due is not taken") {
      val env = Env()
      val now = Instant.parse("2026-11-20T10:00:00Z")
      for
        _ <-
          env.copyAt(settingsVersion = Some(4), listsVersion = 7, fullAt = Some(now.minusSeconds(AntifraudPolicySync.FullCopyInterval.toSeconds - 1)))
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 7))
        _ <- TestClock.setTime(now)
        _ <- env.sync.sync
      yield assertTrue(env.source.lists.calls.isEmpty, !env.writes)
    },
    test("a delta that fails to be read or applied changes nothing, is counted as a failed round, and is asked again next round") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 8))
        _ <- env.source.lists.failsWith(RuntimeException("central answered 500"))
        failed <- env.sync.sync.exit
        wroteWhileDown = env.writes
        failures <- Metric.counter("antifraud_policy_sync_failed_total").value.map(_.count)
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 8, full = false, entries = List(entry), removed = Nil))
        _ <- env.sync.sync
      yield assertTrue(
        failed.isSuccess,
        !wroteWhileDown,
        failures >= 1.0,
        env.source.lists.calls == List(Some(7L), Some(7L)),
        env.lists.applyChanges.calls == List((List(entry), Nil, 7L, 8L)),
      )
    },
    suite("the request for the lists")(
      test("carries the version of the copy as `since`, asks the whole list without it, and does not go through the snapshot") {
        val seen = scala.collection.mutable.ListBuffer.empty[(String, Boolean)]
        val central = new CentralSyncTokenService:
          override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
          override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] =
            ZIO.succeed(seen += (request.url.encode -> true)) *> ZIO.succeed(Response.status(Status.InternalServerError))
          override def liveRequest(request: Request): ZIO[Scope, Throwable, Response] =
            ZIO.succeed(seen += (request.url.encode -> false)) *>
              ZIO.succeed(Response.json(PolicyLists(version = 9, full = false, entries = Nil, removed = Nil).toJson))
        val source = AntifraudPolicySource.Impl(TestEnvConfig.coreConfig, central)
        for
          delta <- source.lists(Some(7L))
          whole <- source.lists(None)
        yield assertTrue(
          delta.version == 9L,
          whole.version == 9L,
          seen.toList.map(_._1.dropWhile(_ != '?')) == List("?since=7", ""),
          // `true` is a request that went through `syncRequest`, where a GET is recorded in and served from the snapshot.
          seen.forall(!_._2),
        )
      },
    ),
    test("a lists response that fails after the state was read does not fail sync, and the lists are read again next round") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 8))
        _ <- env.source.lists.failsWith(RuntimeException("central answered 500"))
        failed <- env.sync.sync.exit
        wroteWhileDown = env.writes
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 8, full = true, entries = List(entry), removed = Nil))
        _ <- env.sync.sync
      yield assertTrue(failed.isSuccess, !wroteWhileDown, env.whole == List((List(entry), 8L)))
    },
    test("a repository that fails does not fail sync, and the next round that works writes") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 5, settings = Some(tuning), listsVersion = 8))
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 8, full = true, entries = List(entry), removed = Nil))
        _ <- env.settings.replace.failsWith(RuntimeException("db down"))
        failed <- env.sync.sync.exit
        listsWhileDown = env.lists.replaceAll.calls
        _ <- env.settings.replace.succeedsWith(())
        _ <- env.sync.sync
      yield assertTrue(
        failed.isSuccess,
        // the round stopped at the settings: the lists are not written ahead of them
        listsWhileDown.isEmpty,
        env.whole == List((List(entry), 8L)),
        env.settings.replace.calls.size == 2,
      )
    },
    test("a settings copy that cannot be read is at no version: it is written again from central's, whichever version central is at") {
      val env = Env()
      for
        _ <- env.lists.copied.succeedsWith(AntifraudListRepository.Copy(7L, Some(Instant.EPOCH)))
        _ <- env.settings.replace.succeedsWith(())
        _ <- env.settings.find.failsWith(RuntimeException("invalid extra field blokAt"))
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 5, settings = Some(tuning), listsVersion = 7))
        _ <- env.sync.sync
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 0, settings = None, listsVersion = 7))
        _ <- env.sync.sync
      yield assertTrue(
        env.settings.replace.calls.map(c => (c._1, c._2)) == List((Some(tuning), 5L), (None, 0L)),
        env.lists.replaceAll.calls.isEmpty,
      )
    },
    test("a settings copy that cannot be read and cannot be replaced does not fail sync, and the lists are not written ahead of it") {
      val env = Env()
      for
        _ <- env.lists.copied.succeedsWith(AntifraudListRepository.Copy(7L, Some(Instant.EPOCH)))
        _ <- env.lists.replaceAll.succeedsWith(true)
        _ <- env.settings.find.failsWith(RuntimeException("invalid extra field blokAt"))
        _ <- env.settings.replace.failsWith(RuntimeException("db down"))
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 5, settings = Some(tuning), listsVersion = 8))
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 8, full = true, entries = List(entry), removed = Nil))
        failed <- env.sync.sync.exit
        _ <- env.settings.replace.succeedsWith(())
        _ <- env.sync.sync
      yield assertTrue(
        failed.isSuccess,
        env.settings.replace.calls.size == 2,
        env.whole == List((List(entry), 8L)),
      )
    },
  )
