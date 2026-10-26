package versola.antifraud

import versola.auth.TestEnvConfig
import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.{SecureRandom, SecurityService, UnitSpecBase}
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object VelocityCountersSpec extends UnitSpecBase:

  private val t0 = Instant.parse("2026-10-21T10:00:00Z")
  private val on = AntifraudTestSettings.velocity.copy(enabled = true)

  import VelocityKey.*
  import VelocityWindow.*

  /** What the instances write, in memory; `failing` makes every call fail, as a database that is down does. */
  private class InMemoryRepository(rows: Ref[Map[(UUID, VelocityKey, String, Long), Int]], failing: Ref[Boolean]) extends VelocityRepository:
    private def check = failing.get.flatMap(ZIO.fail(RuntimeException("db down")).when(_)).unit

    override def save(instance: UUID, buckets: List[VelocityBucket]): Task[Unit] =
      check *> rows.update(_ ++ buckets.map(b => (instance, b.keyType, b.key, b.minute) -> b.count))

    override def loadOthers(instance: UUID, fromMinute: Long, toMinute: Long, maxKeys: Int): Task[List[SharedBucket]] =
      check *> rows.get.map: all =>
        val others = all.toList.filter((k, _) => k._1 != instance && k._4 >= fromMinute && k._4 < toMinute)
        val hot = others.groupMapReduce((k, _) => (k._2, k._3))((_, count) => count)(_ + _).toList.sortBy(-_._2).take(maxKeys).map(_._1).toSet
        others.filter((k, _) => hot((k._2, k._3))).map((k, count) =>
          SharedBucket(instance = k._1, bucket = VelocityBucket(keyType = k._2, key = k._3, minute = k._4, count = count)),
        )

    def all: UIO[Map[(UUID, VelocityKey, String, Long), Int]] = rows.get

    override def prune(minute: Long, batch: Int): Task[Int] =
      check *> rows.modify: all =>
        val gone = all.keys.filter(_._4 < minute).take(batch).toSet
        (gone.size, all -- gone)

  private val repository: UIO[(InMemoryRepository, Ref[Boolean])] =
    for
      rows <- Ref.make(Map.empty[(UUID, VelocityKey, String, Long), Int])
      failing <- Ref.make(false)
    yield (InMemoryRepository(rows, failing), failing)

  private def instance(repo: VelocityRepository, settings: VelocityCounters.Settings): ZIO[Scope, Nothing, VelocityCounters] =
    VelocityCounters.live.build.map(_.get).provideSome[Scope](
      ZLayer.succeed(repo),
      ZLayer.succeed(settings),
      ZLayer.succeed[AntifraudTuning](AntifraudTestSettings.tuning(AntifraudTestSettings.decision, settings)),
      ZLayer.succeed(TestEnvConfig.coreConfig),
      SecureRandom.live,
      SecureRandom.live >>> SecurityService.live,
    )

  /** Lets the flush and load fibers of the instances run a round. */
  private def rounds(n: Int): UIO[Unit] =
    ZIO.foreachDiscard(1 to n)(_ => TestClock.adjust(10.seconds) *> Live.live(ZIO.sleep(30.millis)))

  def spec = suite("VelocityCounters")(
    test("counts exactly what many fibers record at once, per key") {
      val counters = VelocityCounters(on)
      for
        _ <- ZIO.foreachParDiscard(1 to 200): fiber =>
          ZIO.succeed:
            (1 to 500).foreach: i =>
              counters.record(ip, s"10.0.0.${i % 3}", t0)
              counters.record(phone, "h", t0.plusSeconds(i % 120))
      yield assertTrue(
        // 100_000 records over 3 keys: 500 / 3 each, remainder to the first.
        counters.count(ip, "10.0.0.0", OneMinute, t0) == 200 * 166,
        counters.count(ip, "10.0.0.1", OneDay, t0) == 200 * 167,
        counters.count(ip, "10.0.0.2", OneHour, t0) == 200 * 167,
        counters.count(phone, "h", OneHour, t0.plusSeconds(120)) == 200 * 500,
        counters.count(device, "h", OneHour, t0.plusSeconds(120)) == 0,
      )
    },
    test("many parallel recorders on one hot key finish quickly and lose nothing") {
      val counters = VelocityCounters(on)
      for
        _ <- ZIO.foreachParDiscard(1 to 256)(_ =>
          ZIO.succeed((1 to 4000).foreach(i => counters.record(prefix, "tenant/4915", t0.plusSeconds(i % 1800)))),
        ).timeoutFail("too slow")(30.seconds)
      yield assertTrue(
        counters.count(prefix, "tenant/4915", OneDay, t0.plusSeconds(1799)) == 256 * 4000,
        // The offsets 1740 to 1799 s, which are the last minute: i = 1740..1799 and 3540..3599.
        counters.count(prefix, "tenant/4915", OneMinute, t0.plusSeconds(1799)) == 256 * 120,
      )
    },
    test("a window is the current minute and the ones before it, and older minutes drop out") {
      val counters = VelocityCounters(on)
      counters.record(ip, "a", t0)
      counters.record(ip, "a", t0.plusSeconds(61))
      val at = t0.plusSeconds(61)
      assertTrue(
        counters.count(ip, "a", OneMinute, at) == 1,
        counters.count(ip, "a", TenMinutes, at) == 2,
        counters.count(ip, "a", TenMinutes, t0.plusSeconds(10 * 60)) == 1,
        counters.count(ip, "a", TenMinutes, t0.plusSeconds(11 * 60)) == 0,
        counters.count(ip, "a", OneHour, t0.plusSeconds(59 * 60)) == 2,
        counters.count(ip, "a", OneHour, t0.plusSeconds(60 * 60)) == 1,
        counters.count(ip, "a", OneDay, t0.plusSeconds(24 * 3600)) == 1,
        counters.count(ip, "a", OneDay, t0.plusSeconds(25 * 3600)) == 0,
      )
    },
    test("a late or out-of-order record lands in its own minute") {
      val counters = VelocityCounters(on)
      counters.record(ip, "a", t0.plusSeconds(300))
      counters.record(ip, "a", t0)
      counters.record(ip, "a", t0.plusSeconds(120))
      assertTrue(
        counters.count(ip, "a", OneMinute, t0.plusSeconds(300)) == 1,
        counters.count(ip, "a", TenMinutes, t0.plusSeconds(300)) == 3,
        counters.count(ip, "a", OneMinute, t0) == 1,
      )
    },
    test("a key busy for more than a day keeps at most a day of minutes") {
      val counters = VelocityCounters(on)
      (0 until 3000).foreach(minute => counters.record(ip, "busy", t0.plusSeconds(minute * 60L)))
      val now = t0.plusSeconds(2999 * 60L)
      assertTrue(counters.count(ip, "busy", OneDay, now) == 1440, counters.count(ip, "busy", OneHour, now) == 60)
    },
    test("evicts keys idle for a day and keeps the others") {
      val counters = VelocityCounters(on)
      counters.record(ip, "old", t0)
      counters.record(ip, "recent", t0.plusSeconds(20 * 3600))
      counters.record(phone, "old", t0)
      counters.evict(t0.plusSeconds(25 * 3600))
      assertTrue(counters.size == 1, counters.count(ip, "recent", OneDay, t0.plusSeconds(25 * 3600)) == 1)
    },
    test("a full table makes room for a new key by dropping the oldest key seen once, and keeps the established ones") {
      val counters = VelocityCounters(on.copy(maxKeys = 3))
      counters.record(ip, "hot", t0)
      counters.record(ip, "hot", t0)
      (1 to 1000).foreach(i => counters.record(ip, s"junk$i", t0))
      assertTrue(
        counters.size == 3,
        counters.count(ip, "hot", OneDay, t0) == 2,
        // The newest of the junk is there, the oldest is gone.
        counters.count(ip, "junk1000", OneDay, t0) == 1,
        counters.count(ip, "junk1", OneDay, t0) == 0,
        counters.evicted == 998,
        counters.untracked == 0,
      )
    },
    test("fails open for a new key, and counts it, only when every key held has been seen again; per kind") {
      val counters = VelocityCounters(on.copy(maxKeys = 2))
      List("a", "b").foreach(k => (1 to 2).foreach(_ => counters.record(ip, k, t0)))
      counters.record(ip, "c", t0)
      counters.record(ip, "a", t0)
      counters.record(phone, "x", t0)
      assertTrue(
        counters.size == 3,
        counters.count(ip, "a", OneDay, t0) == 3,
        counters.count(ip, "c", OneDay, t0) == 0,
        counters.untracked == 1,
        counters.evicted == 0,
        counters.count(phone, "x", OneDay, t0) == 1,
      )
    },
    test("cuts a key to a bounded length") {
      val counters = VelocityCounters(on)
      counters.record(ip, "x" * 10000, t0)
      assertTrue(counters.count(ip, "x" * VelocityCounters.maxKeyLength, OneDay, t0) == 1)
    },
    test("drains only the minutes that changed, at the absolute total, and a failed write is made good") {
      val counters = VelocityCounters(on.copy(shareFrom = 1))
      counters.record(ip, "a", t0)
      counters.record(ip, "a", t0)
      val first = counters.drain(t0, 100)
      val none = counters.drain(t0, 100)
      counters.record(ip, "a", t0.plusSeconds(60))
      val second = counters.drain(t0.plusSeconds(60), 100)
      counters.unflushed(second)
      val again = counters.drain(t0.plusSeconds(60), 100)
      assertTrue(
        first.map(_._1.count) == List(2),
        none.isEmpty,
        second.map(_._1.count) == List(1),
        again.map(_._1.count) == List(1),
      )
    },
    test("drains the busiest keys first and never more rows than the limit, and the rest follows") {
      val counters = VelocityCounters(on.copy(shareFrom = 1))
      (1 to 50).foreach(i => counters.record(prefix, s"junk$i", t0))
      (1 to 30).foreach(_ => counters.record(asn, "64500", t0))
      (1 to 5).foreach(_ => counters.record(phone, "h", t0))
      val first = counters.drain(t0, 3)
      val second = counters.drain(t0, 100)
      assertTrue(
        first.map(_._1.key).take(2) == List("64500", "h"),
        first.size == 3,
        second.size == 50 - 1,
      )
    },
    test("shares a key only from shareFrom requests, and no more than the limit at a time") {
      val counters = VelocityCounters(on.copy(shareFrom = 3))
      counters.record(ip, "quiet", t0)
      counters.record(ip, "quiet", t0)
      val before = counters.drain(t0, 100)
      counters.record(ip, "quiet", t0)
      (1 to 5).foreach(i => counters.record(ip, s"k$i", t0))
      (1 to 5).foreach(i => (1 to 3).foreach(_ => counters.record(phone, s"k$i", t0)))
      val limited = counters.drain(t0, 2)
      assertTrue(before.isEmpty, limited.size == 2, counters.drain(t0, 100).size == 4)
    },
    test("instances add what the others flushed: a count converges within a flush and a load interval") {
      val settings = on.copy(shareFrom = 1)
      for
        (repo, _) <- repository
        _ <- TestClock.setTime(t0)
        a <- instance(repo, settings)
        b <- instance(repo, settings)
        _ <- ZIO.succeed((1 to 7).foreach(_ => a.record(ip, "shared", t0)))
        _ <- ZIO.succeed((1 to 5).foreach(_ => b.record(ip, "shared", t0)))
        before = (a.count(ip, "shared", TenMinutes, t0), b.count(ip, "shared", TenMinutes, t0))
        _ <- rounds(6)
        after = (a.count(ip, "shared", TenMinutes, t0), b.count(ip, "shared", TenMinutes, t0))
      yield assertTrue(before == (7, 5), after == (12, 12))
    },
    test("minutes an instance wrote late, older than the recent ones, are found by the slices of the load") {
      val settings = on.copy(shareFrom = 1)
      for
        (repo, _) <- repository
        _ <- TestClock.setTime(t0)
        a <- instance(repo, settings)
        b <- instance(repo, settings)
        _ <- rounds(1)
        // Written after b's first load, for a minute five hours back, as an instance does when its database was down.
        _ <- ZIO.succeed(a.record(ip, "late", t0.minusSeconds(5 * 3600)))
        _ <- rounds(3)
        soon = b.count(ip, "late", OneDay, t0)
        _ <- rounds(40)
      yield assertTrue(soon == 0, b.count(ip, "late", OneDay, t0) == 1)
    },
    test("a database that is down leaves the per-instance count, and what was not written is written later") {
      val settings = on.copy(shareFrom = 1)
      for
        (repo, failing) <- repository
        _ <- TestClock.setTime(t0)
        a <- instance(repo, settings)
        b <- instance(repo, settings)
        _ <- failing.set(true)
        _ <- ZIO.succeed(a.record(ip, "x", t0))
        _ <- rounds(4)
        down = (a.count(ip, "x", TenMinutes, t0), b.count(ip, "x", TenMinutes, t0))
        _ <- failing.set(false)
        _ <- rounds(6)
        up = b.count(ip, "x", TenMinutes, t0)
      yield assertTrue(down == (1, 0), up == 1)
    },
    test("an address is counted as its network under a keyed hash: spellings and a /64 are one key, and no address is stored") {
      val settings = on.copy(shareFrom = 1, thresholds = AntifraudTestSettings.thresholds.copy(ip = AntifraudTestSettings.limit.copy(perDay = 1000)))
      def ctx(ip: String) = AntifraudContext(
        tenantId = None,
        clientId = ClientId("c"),
        action = AntifraudAction.otp_request,
        subjectHash = None,
        destPrefix = None,
        deviceId = None,
        ip = Some(ip),
        asn = None,
        userAgent = None,
        ja4 = None,
        signals = Map.empty,
      )
      for
        (repo, _) <- repository
        _ <- TestClock.setTime(t0)
        a <- instance(repo, settings)
        _ <- a.record(ctx("2001:db8:0:1::1"))
        _ <- a.record(ctx("2001:0db8:0000:0001:ffff:ffff:ffff:ffff"))
        _ <- a.record(ctx("203.0.113.9"))
        _ <- a.record(ctx("::ffff:203.0.113.9"))
        _ <- a.record(ctx("not an address, www.example.com"))
        v6 <- a.snapshot(ctx("2001:db8:0:1::5"))
        v4 <- a.snapshot(ctx("203.0.113.9"))
        other <- a.snapshot(ctx("2001:db8:0:2::1"))
        _ <- rounds(2)
        stored <- repo.all
      yield assertTrue(
        v6.signals("velocity.ip.24h") == "3",
        v4.signals("velocity.ip.24h") == "3",
        other.signals("velocity.ip.24h") == "1",
        a.size == 2,
        stored.keys.map(_._3).forall(key => !key.contains("203") && !key.contains("2001") && !key.contains(":")),
        stored.size == 2,
      )
    },
    test("a load reads only what changed, and a rewritten minute replaces the old one") {
      val settings = on.copy(shareFrom = 1)
      for
        (repo, _) <- repository
        _ <- TestClock.setTime(t0)
        a <- instance(repo, settings)
        b <- instance(repo, settings)
        _ <- ZIO.succeed((1 to 2).foreach(_ => a.record(phone, "p", t0)))
        _ <- rounds(4)
        first = b.count(phone, "p", TenMinutes, t0)
        _ <- ZIO.succeed((1 to 3).foreach(_ => a.record(phone, "p", t0)))
        _ <- rounds(6)
      yield assertTrue(first == 2, b.count(phone, "p", TenMinutes, t0) == 5)
    },
    test("while off nothing is counted or started") {
      val counters = AntifraudTestSettings.velocityOff
      for
        ctx <- counters.recordAndSnapshot(AntifraudContext(
          tenantId = None,
          clientId = ClientId("c"),
          action = AntifraudAction.otp_request,
          subjectHash = None,
          destPrefix = None,
          deviceId = None,
          ip = Some("1.1.1.1"),
          asn = None,
          userAgent = None,
          ja4 = None,
          signals = Map.empty,
        ))
      yield assertTrue(ctx.signals.isEmpty, counters.size == 0)
    },
  )
