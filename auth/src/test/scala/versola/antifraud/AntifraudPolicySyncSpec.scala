package versola.antifraud

import versola.util.UnitSpecBase
import zio.*
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

    /** The copy is at the given versions: the settings row (none at all for `None`) and the lists. */
    def copyAt(settingsVersion: Option[Long], listsVersion: Long) =
      settings.find.succeedsWith(settingsVersion.map(StoredTuning(tuning, _))) *>
        lists.version.succeedsWith(listsVersion) *>
        settings.replace.succeedsWith(()) *>
        lists.replaceAll.succeedsWith(true)

    def writes: Boolean = settings.replace.calls.nonEmpty || lists.replaceAll.calls.nonEmpty

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
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
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
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 9, entries = List(entry)))
        _ <- env.sync.sync
      yield assertTrue(
        env.source.lists.calls.size == 1,
        env.lists.replaceAll.calls == List((List(entry), 9L)),
        env.settings.replace.calls.isEmpty,
      )
    },
    test("settings and lists that both differ are both written") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(1), listsVersion = 1)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 2, settings = Some(tuning), listsVersion = 2))
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 2, entries = Nil))
        _ <- env.sync.sync
      yield assertTrue(env.settings.replace.calls.map(_._2) == List(2L), env.lists.replaceAll.calls == List((Nil, 2L)))
    },
    test("a source that fails does not fail sync and leaves the copy; the next round that works writes") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.source.state.failsWith(RuntimeException("central is down"))
        down <- env.sync.sync.exit
        wroteWhileDown = env.writes
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 5, settings = Some(tuning), listsVersion = 8))
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 8, entries = List(entry)))
        _ <- env.sync.sync
      yield assertTrue(
        down.isSuccess,
        !wroteWhileDown,
        env.settings.replace.calls.map(_._2) == List(5L),
        env.lists.replaceAll.calls == List((List(entry), 8L)),
      )
    },
    test("a lists response that fails after the state was read does not fail sync, and the lists are read again next round") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 4, settings = Some(tuning), listsVersion = 8))
        _ <- env.source.lists.failsWith(RuntimeException("central answered 500"))
        failed <- env.sync.sync.exit
        wroteWhileDown = env.writes
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 8, entries = List(entry)))
        _ <- env.sync.sync
      yield assertTrue(failed.isSuccess, !wroteWhileDown, env.lists.replaceAll.calls == List((List(entry), 8L)))
    },
    test("a repository that fails does not fail sync, and the next round that works writes") {
      val env = Env()
      for
        _ <- env.copyAt(settingsVersion = Some(4), listsVersion = 7)
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 5, settings = Some(tuning), listsVersion = 8))
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 8, entries = List(entry)))
        _ <- env.settings.replace.failsWith(RuntimeException("db down"))
        failed <- env.sync.sync.exit
        listsWhileDown = env.lists.replaceAll.calls
        _ <- env.settings.replace.succeedsWith(())
        _ <- env.sync.sync
      yield assertTrue(
        failed.isSuccess,
        // the round stopped at the settings: the lists are not written ahead of them
        listsWhileDown.isEmpty,
        env.lists.replaceAll.calls == List((List(entry), 8L)),
        env.settings.replace.calls.size == 2,
      )
    },
    test("a settings copy that cannot be read is at no version: it is written again from central's, whichever version central is at") {
      val env = Env()
      for
        _ <- env.lists.version.succeedsWith(7L)
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
        _ <- env.lists.version.succeedsWith(7L)
        _ <- env.lists.replaceAll.succeedsWith(true)
        _ <- env.settings.find.failsWith(RuntimeException("invalid extra field blokAt"))
        _ <- env.settings.replace.failsWith(RuntimeException("db down"))
        _ <- env.source.state.succeedsWith(PolicyState(settingsVersion = 5, settings = Some(tuning), listsVersion = 8))
        _ <- env.source.lists.succeedsWith(PolicyLists(version = 8, entries = List(entry)))
        failed <- env.sync.sync.exit
        _ <- env.settings.replace.succeedsWith(())
        _ <- env.sync.sync
      yield assertTrue(
        failed.isSuccess,
        env.settings.replace.calls.size == 2,
        env.lists.replaceAll.calls == List((List(entry), 8L)),
      )
    },
  )
