package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.{Patch, UnitSpecBase}
import versola.audit.AuditProbe
import versola.util.audit.AuditEvent
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

object AntifraudTuningSpec extends UnitSpecBase:

  private val at = Instant.parse("2026-10-26T10:00:00Z")

  private def blockAt(value: Int) = TuningPatch.empty.copy(blockAt = Some(Patch.Modified(value)))

  private def challengeAt(value: Int) = TuningPatch.empty.copy(challengeAt = Some(Patch.Modified(value)))

  /** A patch as it arrives, from the wire. */
  private def patchOf(text: String): Task[TuningPatch] = ZIO.fromEither(text.fromJson[TuningPatch]).mapError(RuntimeException(_))

  /** Enforcing, with everything else off: what the specs of the engine start from. */
  private val base = AntifraudTestSettings.tuning

  /** The repository in memory, with a version that moves with every change and a version of the row, as the real one has;
    * `row` is the seeded one. */
  private class InMemory(row: Ref[Option[StoredTuning]], counter: Ref[Long], failing: Ref[Boolean]) extends AntifraudSettingsRepository:
    override def put(tuning: Tuning, actor: String, at: Instant, expectedVersion: Option[Long], audit: AuditEvent) =
      row.modify: current =>
        val version = current.fold(0L)(_.version)
        if expectedVersion.exists(_ != version) then (None, current)
        else (Some(version + 1), Some(StoredTuning(tuning, version + 1)))
      .tap(written => counter.update(_ + 1).when(written.isDefined))

    override def seed(tuning: Tuning, actor: String, at: Instant) =
      row.modify(r => (r.isEmpty, r.orElse(Some(StoredTuning(tuning, 1))))).tap(wrote => counter.update(_ + 1).when(wrote))

    override def find = row.get

    override def version: Task[Long] = failing.get.flatMap(f => if f then ZIO.fail(RuntimeException("down")) else counter.get)

  private def repository(seeded: Boolean, down: Boolean = false) =
    for
      row <- Ref.make(Option.when(seeded)(StoredTuning(base, 1)))
      counter <- Ref.make(0L)
      failing <- Ref.make(down)
    yield (InMemory(row, counter, failing), failing)

  /** The store over a repository that has the seeded row, read once. */
  private val storeLayer: UIO[(AntifraudTuningStore.Impl, Ref[Boolean], InMemory)] =
    for
      (repo, failing) <- repository(seeded = true)
      store = AntifraudTuningStore.Impl(repo)
      _ <- store.refresh
    yield (store, failing, repo)

  private val ctx =
    AntifraudContext(Some(TenantId("a")), ClientId("c"), AntifraudAction.otp_request, None, None, None, None, None, None, None, Map.empty)

  private def rule(name: String, weight: Int, forces: Option[DecisionAction] = None) = AntifraudRule(name, weight, forces, _ => true)

  private def rules(rs: AntifraudRule*): Antifraud.RuleSet = new Antifraud.RuleSet:
    def rules = ZIO.succeed(rs.toList)

  private val enabled = AntifraudTestSettings.decision.copy(enabled = true)

  def spec = suite("AntifraudTuning")(
    test("the seeded row applies, and everything is off") {
      for (store, _, _) <- storeLayer
      yield assertTrue(store.available, store.current == base, !store.current.smsLimits.enabled)
    },
    test("before the first read nothing is taken to be the thresholds: a request gets the failure action, and the tuning is off") {
      for
        (repo, failing) <- repository(seeded = true, down = true)
        store = AntifraudTuningStore.Impl(repo)
        _ <- store.refresh
        before = store.available
        engine = Antifraud.Impl(enabled.copy(failureAction = DecisionAction.Challenge), List(rules(rule("a", 1))), store)
        cold <- engine.evaluate(ctx)
        _ <- failing.set(false)
        _ <- store.refresh
        warm <- engine.evaluate(ctx)
      yield assertTrue(
        !before,
        cold == Decision(DecisionAction.Challenge, 0, List("antifraud_tuning_unavailable")),
        store.available,
        warm == Decision(DecisionAction.Allow, 1, List("a")),
      )
    },
    test("a change applies on the instance that took it at once, and on another on its next refresh") {
      for
        (writer, _, repo) <- storeLayer
        reader = AntifraudTuningStore.Impl(repo)
        _ <- writer.put(base.copy(blockAt = 70), "alice", None)
        before = reader.current.blockAt
        _ <- reader.refresh
      yield assertTrue(writer.current.blockAt == 70, before == base.blockAt, reader.current.blockAt == 70)
    },
    test("a refresh that finds nothing new still says when it looked, and one that fails does not") {
      val when = Instant.parse("2026-11-02T10:00:00Z")
      // The gauge is the process's and other specs refresh on their own clocks: look again if one got in between.
      def stamp(store: AntifraudTuningStore.Impl) =
        (store.refresh *> zio.metrics.Metric.gauge("antifraud_tuning_refreshed_timestamp_seconds").value.map(_.value))
          .repeatUntil(_ == when.getEpochSecond.toDouble).timeout(5.seconds).map(_.isDefined)
      for
        (store, failing, _) <- storeLayer
        _ <- TestClock.setTime(when)
        moved <- stamp(store)
        _ <- failing.set(true)
        _ <- TestClock.setTime(when.plusSeconds(30))
        _ <- store.refresh
        stamped <- zio.metrics.Metric.gauge("antifraud_tuning_refreshed_timestamp_seconds").value.map(_.value)
      yield assertTrue(moved, stamped != when.plusSeconds(30).getEpochSecond.toDouble)
    },
    test("a refresh that fails keeps the tuning there is") {
      for
        (store, failing, _) <- storeLayer
        _ <- store.put(base.copy(blockAt = 70), "alice", None)
        _ <- failing.set(true)
        _ <- store.refresh
      yield assertTrue(store.current.blockAt == 70, store.available)
    },
    test("no row yet is everything off, and the engine runs on it") {
      for
        (repo, _) <- repository(seeded = false)
        store = AntifraudTuningStore.Impl(repo)
        _ <- store.refresh
        decision <- Antifraud.Impl(enabled.copy(failureAction = DecisionAction.Challenge), List(rules(rule("a", 1))), store).evaluate(ctx)
      yield assertTrue(store.available, store.current == Tuning.off, decision == Decision(DecisionAction.Allow, 1, List("a")))
    },
    test("reset puts the seeded values back") {
      for
        (store, _, _) <- storeLayer
        _ <- store.put(base.copy(blockAt = 70, shadowRules = Set("velocity_ip_1m")), "alice", None)
        _ <- store.reset("alice", None)
      yield assertTrue(store.current == Tuning.off, store.current.mode == TuningMode.shadow)
    },
    test("a change made on the version that was read moves the version; one made on an older version is refused and writes nothing") {
      for
        (store, _, _) <- storeLayer
        first <- store.stored
        v2 <- store.put(base.copy(blockAt = 70), "alice", Some(first.version))
        stale <- store.put(base.copy(blockAt = 80), "bob", Some(first.version)).either
        after <- store.stored
        v3 <- store.reset("carol", Some(v2))
        resetStale <- store.reset("dave", Some(v2)).either
      yield assertTrue(
        first.version == 1L,
        v2 == 2L,
        stale.left.exists { case m: VersionMismatch => m.current == 2L; case _ => false },
        after == StoredTuning(base.copy(blockAt = 70), 2),
        store.current == Tuning.off,
        v3 == 3L,
        resetStale.left.exists(_.isInstanceOf[VersionMismatch]),
      )
    },
    test("a patch changes the keys it names, merges nested objects, removes a map entry with null, replaces a set, and leaves the rest") {
      val withLimits = base.copy(
        smsLimits = SmsLimits(SmsLimit(30, 0, 0.1), Map("49" -> SmsLimit(25, 25, 0.2), "7" -> SmsLimit(40, 0, 0.1))),
        ruleWeights = Map("velocity_phone_1h" -> 40, "velocity_ip_1m" -> 5),
        shadowRules = Set("velocity_ip_1h", "velocity_ip_24h"),
        blockAt = 50,
      )
      for
        (store, _, _) <- storeLayer
        first <- store.stored
        _ <- store.put(withLimits, "alice", Some(first.version))
        patch <- patchOf(
          """{"blockAt":80,"smsLimits":{"default":{"sentPerHour":20},"byPrefix":{"49":null,"44":{"sentPerHour":21,"minVolume":0,"minConversion":0.3}}},"ruleWeights":{"velocity_ip_1m":null,"velocity_asn_1h":7},"shadowRules":["velocity_asn_24h"],"pow":{"maxDifficulty":20}}""",
        )
        patched <- store.patch(patch, "bob", None)
        stored <- store.stored
      yield assertTrue(
        patched.version == 3L,
        stored == patched,
        stored.tuning.blockAt == 80,
        stored.tuning.challengeAt == withLimits.challengeAt,
        stored.tuning.smsLimits.default == SmsLimit(20, 0, 0.1),
        stored.tuning.smsLimits.byPrefix == Map("7" -> SmsLimit(40, 0, 0.1), "44" -> SmsLimit(21, 0, 0.3)),
        stored.tuning.ruleWeights == Map("velocity_phone_1h" -> 40, "velocity_asn_1h" -> 7),
        stored.tuning.shadowRules == Set("velocity_asn_24h"),
        stored.tuning.pow == withLimits.pow.copy(maxDifficulty = 20),
        stored.tuning.thresholds == withLimits.thresholds,
        store.current == stored.tuning,
      )
    },
    test("a patch of a deployment with no row is made on everything off, and is the first row") {
      for
        (repo, _) <- repository(seeded = false)
        store = AntifraudTuningStore.Impl(repo)
        patched <- store.patch(blockAt(60), "alice", Some(0))
      yield assertTrue(patched == StoredTuning(Tuning.off.copy(blockAt = 60), 1))
    },
    test("a patch whose result is not valid or out of range is refused as InvalidTuning and writes nothing") {
      for
        (store, _, _) <- storeLayer
        negative <- store.patch(blockAt(-5), "alice", None).either
        tooLowLimit <- patchOf("""{"smsLimits":{"default":{"sentPerHour":5}}}""").flatMap(store.patch(_, "alice", None).either)
        tooHard <- patchOf("""{"pow":{"maxDifficulty":30}}""").flatMap(store.patch(_, "alice", None).either)
        order <- patchOf("""{"challengeAt":90,"blockAt":50}""").flatMap(store.patch(_, "alice", None).either)
        unknownRule <- patchOf("""{"shadowRules":["nope"]}""").flatMap(store.patch(_, "alice", None).either)
        unknownWeight <- patchOf("""{"ruleWeights":{"nope":5}}""").flatMap(store.patch(_, "alice", None).either)
        badPrefix <- patchOf("""{"smsLimits":{"byPrefix":{"+49":{"sentPerHour":0,"minVolume":0,"minConversion":0.1}}}}""").flatMap(
          store.patch(_, "alice", None).either,
        )
        after <- store.stored
      yield assertTrue(
        List(negative, tooLowLimit, tooHard, order, unknownRule, unknownWeight, badPrefix).forall(_.left.exists(_.isInstanceOf[InvalidTuning])),
        after == StoredTuning(base, 1),
      )
    },
    test("a null resets a setting to its default in Tuning.off, and that is written: every setting has one") {
      for
        (store, _, _) <- storeLayer
        _ <- store.put(base.copy(blockAt = 70, thresholds = base.thresholds.copy(trustedDiscount = 10)), "alice", Some(1))
        reset <- patchOf("""{"blockAt":null,"thresholds":{"trustedDiscount":null}}""").flatMap(store.patch(_, "bob", None))
        everything <- patchOf("""{"thresholds":null,"smsLimits":null,"pow":null}""").flatMap(store.patch(_, "bob", None))
      yield assertTrue(
        reset == StoredTuning(base, 3),
        everything.tuning == base,
        everything.version == 4L,
      )
    },
    test("a patch on a version the row has left is refused and writes nothing; one on the current version is made") {
      for
        (store, _, _) <- storeLayer
        _ <- store.put(base.copy(blockAt = 70), "alice", Some(1))
        stale <- store.patch(challengeAt(30), "bob", Some(1)).either
        after <- store.stored
        ok <- store.patch(challengeAt(30), "bob", Some(2))
      yield assertTrue(
        stale.left.exists { case m: VersionMismatch => m.current == 2L; case _ => false },
        after == StoredTuning(base.copy(blockAt = 70), 2),
        ok == StoredTuning(base.copy(blockAt = 70, challengeAt = 30), 3),
      )
    },
    test("a patch made without a version, over a change that lands between its read and its write, is made again on what that wrote") {
      // The repository refuses the first write as the row moves on under it, as a concurrent writer would have made it.
      for
        (store, _, repo) <- storeLayer
        racing = new AntifraudSettingsRepository:
          private val raced = AtomicBoolean(false)
          override def put(tuning: Tuning, actor: String, at: Instant, expectedVersion: Option[Long], audit: AuditEvent) =
            if raced.compareAndSet(false, true) then
              repo.put(base.copy(challengeAt = 33), "carol", at, None, audit) *> repo.put(tuning, actor, at, expectedVersion, audit)
            else repo.put(tuning, actor, at, expectedVersion, audit)
          override def seed(tuning: Tuning, actor: String, at: Instant) = repo.seed(tuning, actor, at)
          override def find = repo.find
          override def version = repo.version
        racingStore = AntifraudTuningStore.Impl(racing)
        patched <- racingStore.patch(blockAt(70), "bob", None)
        withVersion <- {
          val again = AntifraudTuningStore.Impl(new AntifraudSettingsRepository:
            private val raced = AtomicBoolean(false)
            override def put(tuning: Tuning, actor: String, at: Instant, expectedVersion: Option[Long], audit: AuditEvent) =
              if raced.compareAndSet(false, true) then repo.put(base, "carol", at, None, audit) *> repo.put(tuning, actor, at, expectedVersion, audit)
              else repo.put(tuning, actor, at, expectedVersion, audit)
            override def seed(tuning: Tuning, actor: String, at: Instant) = repo.seed(tuning, actor, at)
            override def find = repo.find
            override def version = repo.version)
          again.stored.flatMap(row => again.patch(blockAt(40), "dave", Some(row.version)).either)
        }
      yield assertTrue(
        // made again on what carol wrote: both changes are there
        patched.tuning == base.copy(challengeAt = 33, blockAt = 70),
        patched.version == 3L,
        // with a version named, the same race is a refusal
        withVersion.left.exists(_.isInstanceOf[VersionMismatch]),
      )
    },
    test("there is no row at version 0, which is the version a first change may name") {
      for
        (repo, _) <- repository(seeded = false)
        store = AntifraudTuningStore.Impl(repo)
        none <- store.stored
        wrong <- store.put(base.copy(blockAt = 15), "alice", Some(3)).either
        first <- store.put(base.copy(blockAt = 15), "alice", Some(0))
      yield assertTrue(none == StoredTuning(Tuning.off, 0), wrong.left.exists(_.isInstanceOf[VersionMismatch]), first == 1L)
    },
    test("the status says what the instance holds, when it last checked, and what the last failed refresh said") {
      for
        (store, failing, _) <- storeLayer
        ok <- store.status
        _ <- failing.set(true)
        _ <- store.refresh
        failed <- store.status
        _ <- failing.set(false)
        _ <- store.refresh
        recovered <- store.status
      yield assertTrue(
        ok.available,
        ok.version.contains(1L),
        ok.checkedAt.isDefined,
        ok.lastError.isEmpty,
        failed.available,
        failed.version.contains(1L),
        failed.checkedAt == ok.checkedAt,
        failed.lastError.contains("down"),
        recovered.lastError.isEmpty,
      )
    },
    test("a row stored while there was a Delay step or a fake-send wait still reads: those retired keys are ignored, any other is refused") {
      val stored = base.toJson.dropRight(1) + ""","delayAt":3,"delayMillis":2000,"fakeSendDelayMillis":500}"""
      val unknown = base.toJson.dropRight(1) + ""","blokAt":3}"""
      assertTrue(
        stored.fromJson(using Tuning.storedDecoder) == Right(base),
        // a request is read by the codec itself: the retired keys are unknown there
        stored.fromJson[Tuning].isLeft,
        unknown.fromJson(using Tuning.storedDecoder).isLeft,
        unknown.fromJson[Tuning].isLeft,
      )
    },
    test("refuses what cannot be meant") {
      def refused(t: Tuning) = AntifraudTuningStore.invalid(t).isDefined
      assertTrue(
        refused(base.copy(blockAt = -1)),
        refused(base.copy(challengeAt = 1000000)),
        refused(base.copy(smsLimits = SmsLimits(SmsLimit(0, 0, 0.1), Map("49x" -> SmsLimit(50, 0, 0.1))))),
        refused(base.copy(smsLimits = SmsLimits(SmsLimit(0, 0, 2.0), Map.empty))),
        !refused(base.copy(smsLimits = SmsLimits(SmsLimit(0, 0, 0.1), Map("49" -> SmsLimit(50, 0, 0.1))))),
        refused(base.copy(ruleWeights = Map("Bad Name" -> 5))),
        refused(base.copy(shadowRules = Set(""))),
        refused(base.copy(pow = PowDifficulty(minDifficulty = 20, maxDifficulty = 18, scoreStep = 15))),
        refused(base.copy(pow = PowDifficulty(minDifficulty = 14, maxDifficulty = 99, scoreStep = 15))),
        !refused(base.copy(pow = PowDifficulty(minDifficulty = 10, maxDifficulty = 20, scoreStep = 0))),
        !refused(base),
      )
    },
    test("put does not store what is refused") {
      for
        (store, _, _) <- storeLayer
        result <- store.put(base.copy(blockAt = -5), "alice", None).either
        stored <- store.stored
      yield assertTrue(result.left.exists(_.isInstanceOf[InvalidTuning]), stored == StoredTuning(base, 1))
    },
    test("the engine takes the thresholds that are stored") {
      for
        (store, _, _) <- storeLayer
        engine = Antifraud.Impl(enabled, List(rules(rule("a", 12))), store)
        before <- engine.evaluate(ctx)
        _ <- store.put(base.copy(blockAt = 10), "alice", None)
        after <- engine.evaluate(ctx)
      yield assertTrue(before.action == DecisionAction.Allow, after.action == DecisionAction.Block)
    },
    test("a rule weight overrides the rule's, but not a rule that forces an action") {
      for
        (store, _, _) <- storeLayer
        _ <- store.put(base.copy(blockAt = 10, ruleWeights = Map("velocity_ip_1m" -> 20, "fake_list:ip" -> 1)), "alice", None)
        heavy <- Antifraud.Impl(enabled, List(rules(rule("velocity_ip_1m", 1))), store).evaluate(ctx)
        forced <- Antifraud.Impl(enabled, List(rules(rule("fake_list:ip", 0, Some(DecisionAction.Fake)))), store).evaluate(ctx)
      yield assertTrue(heavy.score == 20, heavy.action == DecisionAction.Block, forced.score == 0, forced.action == DecisionAction.Fake)
    },
    test("a shadow rule is listed as such and adds nothing") {
      for
        (store, _, _) <- storeLayer
        _ <- store.put(base.copy(blockAt = 10, shadowRules = Set("deny_list:ip", "velocity_ip_1m")), "alice", None)
        decision <- Antifraud.Impl(
          enabled,
          List(rules(rule("velocity_ip_1m", 90), rule("b", 1), rule("deny_list:ip", 0, Some(DecisionAction.Block)))),
          store,
        ).evaluate(ctx)
      yield assertTrue(
        decision.score == 1,
        decision.action == DecisionAction.Allow,
        decision.firedRules == List("b", "shadow:velocity_ip_1m", "shadow:deny_list:ip"),
      )
    },
    test("the limit on codes sent follows the longest prefix a number starts with") {
      val limits = SmsLimits(SmsLimit(0, 0, 0.1), Map("49" -> SmsLimit(2, 0, 0.1), "4915" -> SmsLimit(1, 0, 0.1)))
      val tenant = Some(TenantId("a"))
      for
        (store, _, repo) <- storeLayer
        // Stored by hand: the limits of 1 and 2 codes are what the store refuses, and what keeps the counts short here.
        _ <- repo.put(base.copy(smsLimits = limits), "alice", at, None, AuditProbe.sample()) *> store.refresh
        budget = new SmsBudget(AntifraudTestSettings.smsBudget.copy(prefixLength = 6), AntifraudTestSettings.smsSizing, () => store.current.smsLimits)
        first = budget.decide(tenant, "491512", at, charge = true)
        second = budget.decide(tenant, "491512", at, charge = true)
        german = (
          budget.decide(tenant, "491612", at, charge = true),
          budget.decide(tenant, "491612", at, charge = true),
          budget.decide(tenant, "491612", at, charge = true),
        )
        elsewhere = (1 to 5).map(_ => budget.decide(tenant, "771012", at, charge = true)).toSet
      yield assertTrue(
        store.current.smsLimits.enabled,
        first == SmsBudget.Decision.Allowed,
        second == SmsBudget.Decision.Refused,
        german == (SmsBudget.Decision.Allowed, SmsBudget.Decision.Allowed, SmsBudget.Decision.Refused),
        elsewhere == Set(SmsBudget.Decision.Allowed),
      )
    },
    test("the velocity rules are built from the limits that are stored") {
      val limit = AntifraudTestSettings.limit.copy(perHour = 2)
      for
        (store, _, _) <- storeLayer
        ruleSet = VelocityRules.ruleSet(AntifraudTestSettings.velocity.copy(enabled = true), store)
        before <- ruleSet.rulesFor(ctx)
        _ <- store.put(base.copy(thresholds = base.thresholds.copy(phone = limit)), "alice", None)
        after <- ruleSet.rulesFor(ctx)
      yield assertTrue(before.isEmpty, after.map(_.name).contains("velocity_phone_1h"))
    },
  )
