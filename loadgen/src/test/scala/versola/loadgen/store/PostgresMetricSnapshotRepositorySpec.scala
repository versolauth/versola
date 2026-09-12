package versola.loadgen.store

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

final case class MetricSnapshotEnv(repository: MetricSnapshotRepository)

/** `vu_metric_snapshots` against a real Postgres.
  *
  * The idempotency this table claims is not in the Scala: it is one `ON CONFLICT` target that
  * has to name exactly the columns `vu_metric_snapshots_identity_idx` indexes, including the
  * `COALESCE(scenario, '')` that makes a flow snapshot -- whose `scenario` is NULL -- comparable
  * at all. Get that wrong and a retried write double-counts its buckets into the campaign's
  * merged latency, which is the number the whole campaign exists to produce.
  */
object PostgresMetricSnapshotRepositorySpec extends LoadgenPostgresSpec, DatabaseSpecBase[MetricSnapshotEnv]:

  private val now = Instant.parse("2026-01-01T12:00:00Z")

  private def step(driverId: String, capturedAt: Instant, name: String = "authorize"): MetricSnapshotRow =
    MetricSnapshotRow(
      campaign = "nightly",
      driverId = driverId,
      capturedAt = capturedAt,
      wireVersion = 1,
      kind = MeasurementKind.Step,
      scenario = Some("mobile-login"),
      name = name,
      unit = "ms",
      sampleCount = 1024,
      histogram = "HISTFAAAAB4",
    )

  /** §11 labels a flow on its own rather than inside a scenario, so this is the NULL-`scenario`
    * row the identity index has to cope with.
    */
  private def flow(driverId: String, capturedAt: Instant): MetricSnapshotRow =
    step(driverId, capturedAt).copy(kind = MeasurementKind.Flow, scenario = None, name = "login")

  override lazy val environment =
    ZLayer:
      ZIO.serviceWith[TransactorZIO](xa => MetricSnapshotEnv(PostgresMetricSnapshotRepository(xa)))

  override def beforeEach(env: MetricSnapshotEnv) =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(sql"TRUNCATE TABLE vu_metric_snapshots".update.run()).unit

  override def testCases(env: MetricSnapshotEnv) = List(
    test("appendAll then loadCampaign round-trips a snapshot, histogram payload included") {
      val snapshot = step("driver-0", now)
      for
        _     <- env.repository.appendAll(Chunk(snapshot))
        found <- env.repository.loadCampaign("nightly", now.minusSeconds(60))
      yield assertTrue(found == Vector(snapshot))
    },
    test("a flow snapshot round-trips with no scenario, which means not applicable and not unknown") {
      val snapshot = flow("driver-0", now)
      for
        _     <- env.repository.appendAll(Chunk(snapshot))
        found <- env.repository.loadCampaign("nightly", now.minusSeconds(60))
      yield assertTrue(found == Vector(snapshot), found.head.scenario.isEmpty)
    },
    test("re-writing a snapshot that already landed adds nothing, so a retry cannot double-count") {
      val snapshot = step("driver-0", now)
      for
        _     <- env.repository.appendAll(Chunk(snapshot))
        _     <- env.repository.appendAll(Chunk(snapshot.copy(sampleCount = 2048, histogram = "HISTFAAAAB5")))
        found <- env.repository.loadCampaign("nightly", now.minusSeconds(60))
      yield assertTrue(found.size == 1, found.head.sampleCount == 1024L)
    },
    test("a retried flow snapshot is deduplicated too, though its scenario is NULL") {
      val snapshot = flow("driver-0", now)
      for
        _     <- env.repository.appendAll(Chunk(snapshot))
        _     <- env.repository.appendAll(Chunk(snapshot))
        found <- env.repository.loadCampaign("nightly", now.minusSeconds(60))
      yield assertTrue(found.size == 1)
    },
    test("a step and a flow of the same interval are different snapshots, not a conflict") {
      for
        _     <- env.repository.appendAll(Chunk(step("driver-0", now), flow("driver-0", now)))
        found <- env.repository.loadCampaign("nightly", now.minusSeconds(60))
      yield assertTrue(found.size == 2)
    },
    test("two drivers' snapshots of the same interval both land") {
      for
        _     <- env.repository.appendAll(Chunk(step("driver-0", now), step("driver-1", now)))
        found <- env.repository.loadCampaign("nightly", now.minusSeconds(60))
      yield assertTrue(found.map(_.driverId) == Vector("driver-0", "driver-1"))
    },
    test("loadCampaign returns the campaign in interval order, driver by driver within one") {
      val later = now.plusSeconds(60)
      for
        _ <- env.repository.appendAll(Chunk(step("driver-1", later), step("driver-1", now), step("driver-0", now)))
        found <- env.repository.loadCampaign("nightly", now.minusSeconds(60))
      yield assertTrue(
        found.map(row => (row.driverId, row.capturedAt)) ==
          Vector(("driver-0", now), ("driver-1", now), ("driver-1", later))
      )
    },
    test("loadCampaign reads one campaign, not the run before it") {
      for
        _     <- env.repository.appendAll(Chunk(step("driver-0", now), step("driver-0", now).copy(campaign = "smoke")))
        found <- env.repository.loadCampaign("nightly", now.minusSeconds(60))
      yield assertTrue(found.map(_.campaign) == Vector("nightly"))
    },
    test("loadCampaign starts at `since`, so a merge can be incremental") {
      val later = now.plusSeconds(60)
      for
        _     <- env.repository.appendAll(Chunk(step("driver-0", now), step("driver-0", later)))
        found <- env.repository.loadCampaign("nightly", later)
      yield assertTrue(found.map(_.capturedAt) == Vector(later))
    },
    test("appendAll on an empty chunk is a no-op, not an empty round trip") {
      for
        _     <- env.repository.appendAll(Chunk.empty)
        found <- env.repository.loadCampaign("nightly", now.minusSeconds(60))
      yield assertTrue(found.isEmpty)
    },
  )
