package versola.loadgen.coordinator

import versola.loadgen.config.{LoadgenConfig, LoadgenConfigSpec, LoadgenRole}
import versola.loadgen.metrics.{ErrorTaxonomy, HistogramSample, HistogramWire, LatencyRecorder, MeasurementId, TokenObservations}
import versola.loadgen.model.{ActivityClass, CredentialKind, Platform, UserRole, VirtualUser, VirtualUserState}
import versola.loadgen.store.{
  MeasurementKind,
  MetricSnapshotRepository,
  MetricSnapshotRow,
  PoolerStatSnapshotRow,
  SutStatPhase,
  SutStatSnapshotRow,
  UserTouch,
  VirtualUserRepository,
}
import versola.loadgen.sut.{
  PoolerStatsCapture,
  PoolerStatsDelta,
  PoolerStatsFixture,
  SutStatsCapture,
  SutStatsDelta,
  SutStatsFixture,
}
import versola.util.Secret
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.{Chunk, Clock, IO, Ref, Task, UIO, ZIO}

import java.time.Instant
import java.util.UUID

/** Shared fixtures for the coordinator suites: a real decoded [[LoadgenConfig]] and in-memory
  * stand-ins for the two repositories and the rebalancer.
  *
  * The config is decoded from `LoadgenConfigSpec`'s HOCON rather than assembled in code, for the
  * same reason that spec says it is not private: a second hand-written copy of a tree this wide
  * drifts the moment a field is added, and these suites are about the plan, not about config
  * literals.
  */
object CoordinatorFixture:

  val coordinatorConfig: IO[zio.Config.Error, LoadgenConfig] =
    TypesafeConfigProvider
      .fromHoconString(LoadgenConfigSpec.hoconWithoutProvision)
      .kebabCase
      .load(deriveConfig[LoadgenConfig])
      .map(_.copy(role = LoadgenRole.Coordinator, shard = None))

  /** The same config with the registration ramp switched on: `campaign.registration.enabled` is
    * false in the shared HOCON (campaign 3 registers nobody), and the controller is campaign 1's.
    */
  val registrationConfig: IO[zio.Config.Error, LoadgenConfig] =
    coordinatorConfig.map: config =>
      config.copy(campaign = config.campaign.copy(registration = config.campaign.registration.copy(enabled = true)))

  /** The ramp with a target a test can actually reach: the shared HOCON's is campaign 1's 1M, and
    * a suite cannot seed a million rows to watch the ramp stop.
    */
  def registrationConfigWithTarget(target: Long): IO[zio.Config.Error, LoadgenConfig] =
    registrationConfig.map: config =>
      config.copy(campaign = config.campaign.copy(registration = config.campaign.registration.copy(target = target)))

  def snapshotRow(
      campaign: String,
      driverId: String,
      capturedAt: Instant,
      id: MeasurementId,
      micros: Long,
      count: Long,
  ): MetricSnapshotRow =
    val histogram = LatencyRecorder.emptyHistogram
    histogram.recordValueWithCount(micros, count)
    val encoded = HistogramWire.encode(HistogramSample(id, histogram))
    val (kind, scenario, name) = id match
      case MeasurementId.Step(scenario, step) => (MeasurementKind.Step, Some(scenario), step)
      case MeasurementId.Flow(flow) => (MeasurementKind.Flow, None, flow)
    MetricSnapshotRow(
      campaign = campaign,
      driverId = driverId,
      capturedAt = capturedAt,
      wireVersion = HistogramWire.version,
      kind = kind,
      scenario = scenario,
      name = name,
      unit = encoded.unit,
      sampleCount = encoded.count,
      histogram = encoded.encoding,
    )

  def driverReport(
      campaign: String,
      driverId: String,
      at: Instant,
      arrivals: Map[PlanScenario, Long],
      taxonomy: ErrorTaxonomy,
      observed: TokenObservations = TokenObservations.empty,
  ): DriverReport =
    DriverReport(
      version = DriverReport.version,
      campaign = campaign,
      driverId = driverId,
      atEpochMillis = at.toEpochMilli,
      epoch = 0L,
      shardIndex = 0,
      arrivals = arrivals,
      taxonomy = taxonomy,
      vitals = healthyVitals,
      observed = observed,
    )

  val healthyVitals: DriverVitals =
    DriverVitals(
      busyUsers = 12,
      inflightRequests = 4,
      scheduleLagP99Micros = Some(180_000L),
      cpuRatio = Some(0.29),
      refreshRejectedTotal = 0L,
      storeFlushDroppedTotal = 0L,
      latencyClampedTotal = 0L,
    )

  def user(id: Long, state: VirtualUserState): VirtualUser =
    VirtualUser(
      id = id,
      sutUserId = None,
      phone = f"+7700000$id%04d",
      password = Option(s"secret-$id"),
      activityClass = ActivityClass.Regular,
      platform = Platform.Mobile,
      credential = CredentialKind.Otp,
      role = UserRole.RetailUser,
      passkeyKey = None,
      passkeyCredId = None,
      state = state,
      shard = 0,
      lastSeenAt = None,
    )

/** `vu_users` in a `Ref`. Only the counts matter to the coordinator -- it reads no user -- but the
  * whole trait is implemented rather than partly stubbed, so this cannot become the reason a test
  * passes.
  */
final class FakeVirtualUsers(users: Ref[Map[Long, VirtualUser]]) extends VirtualUserRepository:

  override def insertAll(inserted: Chunk[VirtualUser]): Task[Unit] =
    users.update(current => current ++ inserted.map(user => user.id -> user))

  override def find(id: Long): Task[Option[VirtualUser]] = users.get.map(_.get(id))

  override def loadShardSlice(shard: Int, afterId: Option[Long], limit: Int): Task[Vector[VirtualUser]] =
    users.get.map(
      _.values.filter(user => user.shard == shard && user.id > afterId.getOrElse(-1L)).toVector.sortBy(_.id).take(limit),
    )

  override def markRegistered(id: Long, sutUserId: UUID): Task[Unit] =
    users.update(
      _.updatedWith(id)(_.map(_.copy(sutUserId = Some(sutUserId), state = VirtualUserState.Registered))),
    )

  override def markBroken(id: Long): Task[Unit] =
    users.update(_.updatedWith(id)(_.map(_.copy(state = VirtualUserState.Broken))))

  override def recordPasskey(id: Long, key: Secret, credentialId: String): Task[Unit] =
    users.update(_.updatedWith(id)(_.map(_.copy(passkeyKey = Some(key), passkeyCredId = Some(credentialId)))))

  override def touchAll(touches: Chunk[UserTouch]): Task[Unit] =
    users.update: current =>
      touches.foldLeft(current): (accumulated, touch) =>
        accumulated.updatedWith(touch.userId)(_.map(_.copy(lastSeenAt = Some(touch.lastSeenAt))))

  override def countByState: Task[Map[VirtualUserState, Long]] =
    users.get.map(_.values.groupMapReduce(_.state)(_ => 1L)(_ + _))

object FakeVirtualUsers:
  def make(users: VirtualUser*): UIO[FakeVirtualUsers] =
    Ref.make(users.map(user => user.id -> user).toMap).map(FakeVirtualUsers(_))

/** `vu_metric_snapshots` in a `Ref`, with the identity index's de-duplication, since the merge's
  * correctness depends on it: a retried write must not double-count its buckets.
  */
final class FakeMetricSnapshots(rows: Ref[Vector[MetricSnapshotRow]]) extends MetricSnapshotRepository:

  override def appendAll(snapshots: Chunk[MetricSnapshotRow]): Task[Unit] =
    rows.update: current =>
      snapshots.foldLeft(current): (accumulated, row) =>
        if accumulated.exists(existing => key(existing) == key(row)) then accumulated else accumulated :+ row

  override def loadCampaign(campaign: String, since: Instant): Task[Vector[MetricSnapshotRow]] =
    rows.get.map(_.filter(row => row.campaign == campaign && !row.capturedAt.isBefore(since)).sortBy(_.capturedAt))

  private def key(row: MetricSnapshotRow) =
    (row.campaign, row.driverId, row.capturedAt, row.kind, row.scenario.getOrElse(""), row.name)

object FakeMetricSnapshots:
  def make(rows: MetricSnapshotRow*): UIO[FakeMetricSnapshots] =
    Ref.make(rows.toVector).map(FakeMetricSnapshots(_))

/** `vu_sut_stat_snapshots` in a `Ref`, with the identity index's first-capture-wins behaviour,
  * and a reading that grows with every capture so a delta computed from a pair is non-zero.
  *
  * The reading itself is the fixture's rather than a real `pg_stat_*` query: what the coordinator
  * owns is *when* the two boundaries are captured, and the queries are [[SutStatsReaderSpec]]'s
  * subject against a real server.
  */
final class FakeSutStats(rows: Ref[Vector[SutStatSnapshotRow]], captures: Ref[Int]) extends SutStatsCapture:

  override def capture(campaign: String, phase: SutStatPhase): UIO[Unit] =
    for
      taken <- captures.updateAndGet(_ + 1)
      now <- Clock.instant
      row = SutStatsFixture.row(
        campaign = campaign,
        database = "auth",
        phase = phase,
        capturedAt = now,
        statsResetAt = None,
        statistics = SutStatsFixture.stats(base = taken.toLong, sizeBytes = taken.toLong * 1024L),
      )
      _ <- rows.update: current =>
        if current.exists(existing => (existing.campaign, existing.database, existing.phase) == (campaign, "auth", phase))
        then current
        else current :+ row
    yield ()

  override def deltas(campaign: String): Task[List[SutStatsDelta]] =
    rows.get.map(recorded => SutStatsDelta.from(recorded.filter(_.campaign == campaign)))

  def phases: UIO[List[SutStatPhase]] = rows.get.map(_.map(_.phase).toList)

object FakeSutStats:
  def make: UIO[FakeSutStats] =
    for
      rows <- Ref.make(Vector.empty[SutStatSnapshotRow])
      captures <- Ref.make(0)
    yield FakeSutStats(rows, captures)

/** [[FakeSutStats]] for `vu_pooler_stat_snapshots`, and for the same reason: what the coordinator
  * owns is which transitions are boundaries, and the admin console commands are
  * [[versola.loadgen.sut.PoolerStatsReader]]'s subject against a real PgBouncer.
  */
final class FakePoolerStats(rows: Ref[Vector[PoolerStatSnapshotRow]], captures: Ref[Int]) extends PoolerStatsCapture:

  override def capture(campaign: String, phase: SutStatPhase): UIO[Unit] =
    for
      taken <- captures.updateAndGet(_ + 1)
      now <- Clock.instant
      row = PoolerStatsFixture.row(
        campaign = campaign,
        pooler = "auth-pooler",
        phase = phase,
        capturedAt = now,
        statistics = PoolerStatsFixture.stats(base = taken.toLong),
      )
      _ <- rows.update: current =>
        if current.exists(existing => (existing.campaign, existing.pooler, existing.phase) == (campaign, "auth-pooler", phase))
        then current
        else current :+ row
    yield ()

  override def deltas(campaign: String): Task[List[PoolerStatsDelta]] =
    rows.get.map(recorded => PoolerStatsDelta.from(recorded.filter(_.campaign == campaign)))

  def phases: UIO[List[SutStatPhase]] = rows.get.map(_.map(_.phase).toList)

object FakePoolerStats:
  def make: UIO[FakePoolerStats] =
    for
      rows <- Ref.make(Vector.empty[PoolerStatSnapshotRow])
      captures <- Ref.make(0)
    yield FakePoolerStats(rows, captures)

/** Records the shard counts it was asked to re-shard onto, and can be made to fail -- the drain
  * protocol's behaviour when the bulk `UPDATE` does not land is the part of it that is easiest to
  * get wrong.
  */
final class FakeRebalancer(val calls: Ref[List[Int]], failing: Ref[Boolean]) extends ShardRebalancer:

  override def reassign(shardCount: Int): Task[Long] =
    for
      _ <- calls.update(_ :+ shardCount)
      broken <- failing.get
      _ <- ZIO.fail(RuntimeException("the store is unreachable")).when(broken)
    yield 1_000L

  def fail: UIO[Unit] = failing.set(true)

  def recover: UIO[Unit] = failing.set(false)

object FakeRebalancer:
  def make: UIO[FakeRebalancer] =
    for
      calls <- Ref.make(List.empty[Int])
      failing <- Ref.make(false)
    yield FakeRebalancer(calls, failing)
