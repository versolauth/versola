package versola.loadgen.coordinator

import zio.test.*

import java.time.{Duration as JavaDuration, Instant}

/** The lifecycle and the drain protocol as pure state transitions -- no clock, no server, no
  * database. Everything about §12's rebalance that can be got wrong lives here, and none of it
  * needs Postgres to be got wrong.
  */
object CampaignControlSpec extends ZIOSpecDefault:

  private val t0 = Instant.parse("2026-09-15T08:00:00Z")

  private val idle = CampaignControl.initial(shardCount = 8)

  private def started: CampaignControl = idle.start(t0).toOption.get

  def spec = suite("CampaignControl")(
    suite("lifecycle")(
      test("starts idle on the configured shard map, with no epoch of its own invention") {
        assertTrue(
          idle.state == CampaignState.Idle,
          idle.shards == ShardMap(epoch = 0L, shardCount = 8),
          idle.startedAt.isEmpty,
          idle.pendingShards.isEmpty,
        )
      },
      test("start anchors the campaign; a second start is a no-op rather than a re-anchor") {
        val again = started.start(t0.plusSeconds(600))
        assertTrue(
          started.state == CampaignState.Running,
          started.startedAt.contains(t0),
          // Re-anchoring would restart the phase timeline and every driver's arrival recurrence
          // mid-campaign, which is a load shape nobody asked for and no error to show for it.
          again.map(_.startedAt) == Right(Some(t0)),
        )
      },
      test("a pause is subtracted from the campaign clock, so the phase resumes where it left") {
        val resumed = for
          paused <- started.pause(t0.plusSeconds(600))
          running <- paused.start(t0.plusSeconds(600 + 1800))
        yield running
        assertTrue(
          // Ten minutes of campaign ran, thirty of wall clock passed, so the anchor moves by the
          // thirty minutes of pause: at the moment of resume the campaign is ten minutes old.
          resumed.map(_.startedAt) == Right(Some(t0.plusSeconds(1800))),
          resumed.map(_.state) == Right(CampaignState.Running),
          resumed.map(_.pausedAt) == Right(None),
          resumed.map(control => JavaDuration.between(control.startedAt.get, t0.plusSeconds(2400))) ==
            Right(JavaDuration.ofSeconds(600)),
        )
      },
      test("stop is terminal: it cannot be restarted, paused or rebalanced") {
        val stopped = started.stop.toOption.get
        assertTrue(
          stopped.state == CampaignState.Stopped,
          stopped.start(t0.plusSeconds(1)).isLeft,
          stopped.pause(t0.plusSeconds(1)).isLeft,
          stopped.publishRebalance(16, t0.plusSeconds(120), t0.plusSeconds(1)).isLeft,
          stopped.stop.map(_.state) == Right(CampaignState.Stopped),
        )
      },
      test("a campaign that never started cannot be paused") {
        assertTrue(idle.pause(t0).isLeft, idle.stop.map(_.state) == Right(CampaignState.Stopped))
      },
    ),
    suite("rebalance")(
      test("publishes the next epoch with a drain deadline, leaving the map in force unchanged") {
        val published = started.publishRebalance(16, t0.plusSeconds(120), t0)
        assertTrue(
          published.map(_.shards) == Right(ShardMap(epoch = 0L, shardCount = 8)),
          published.map(_.pendingShards) == Right(
            Some(ShardMapChange(epoch = 1L, shardCount = 16, drainUntilEpochMillis = t0.plusSeconds(120).toEpochMilli)),
          ),
        )
      },
      test("refuses a second rebalance while one is draining") {
        val draining = started.publishRebalance(16, t0.plusSeconds(120), t0).toOption.get
        assertTrue(draining.publishRebalance(32, t0.plusSeconds(240), t0.plusSeconds(1)).isLeft)
      },
      test("refuses a no-op rebalance, a non-positive count and a deadline in the past") {
        assertTrue(
          started.publishRebalance(8, t0.plusSeconds(120), t0).isLeft,
          started.publishRebalance(0, t0.plusSeconds(120), t0).isLeft,
          started.publishRebalance(16, t0.minusSeconds(1), t0).isLeft,
          started.publishRebalance(16, t0, t0).isLeft,
        )
      },
      test("refuses a map wider than the shard column, while the request can still be answered") {
        // `vu_users.shard` and `vu_sessions.shard` are SMALLINT. A wider map is not caught until
        // the bulk UPDATE, which runs after the drain window has already elapsed -- and `settle`
        // retries that failure with the pending map still in force, so the moved users stay
        // drained for the rest of the campaign with nobody scheduling them.
        assertTrue(
          started.publishRebalance(ShardMap.maxShardCount, t0.plusSeconds(120), t0).isRight,
          started.publishRebalance(ShardMap.maxShardCount + 1, t0.plusSeconds(120), t0).isLeft,
          started.publishRebalance(Int.MaxValue, t0.plusSeconds(120), t0).isLeft,
        )
      },
      test("the map is due only once the drain window has elapsed") {
        val draining = started.publishRebalance(16, t0.plusSeconds(120), t0).toOption.get
        assertTrue(
          draining.dueRebalance(t0.plusSeconds(119)).isEmpty,
          draining.dueRebalance(t0.plusSeconds(120)).nonEmpty,
          draining.dueRebalance(t0.plusSeconds(600)).nonEmpty,
          idle.dueRebalance(t0.plusSeconds(600)).isEmpty,
        )
      },
      test("commit promotes the published map and bumps the epoch exactly once") {
        val draining = started.publishRebalance(16, t0.plusSeconds(120), t0).toOption.get
        val change = draining.dueRebalance(t0.plusSeconds(120)).get
        val committed = draining.commitRebalance(change)
        assertTrue(
          committed.shards == ShardMap(epoch = 1L, shardCount = 16),
          committed.pendingShards.isEmpty,
          // A retried commit -- the settle loop crashing between the UPDATE and the promotion --
          // must not bump the epoch a second time, or every driver reloads its slice for nothing.
          committed.commitRebalance(change) == committed,
        )
      },
      test("a stale commit is ignored rather than applied over a newer map") {
        val first = started.publishRebalance(16, t0.plusSeconds(120), t0).toOption.get
        val stale = first.pendingShards.get
        val committed = first.commitRebalance(stale)
        val second = committed.publishRebalance(32, t0.plusSeconds(600), t0.plusSeconds(200)).toOption.get
        assertTrue(second.commitRebalance(stale) == second)
      },
    ),
  )
