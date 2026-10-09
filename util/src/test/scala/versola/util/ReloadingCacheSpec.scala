package versola.util

import zio.*
import zio.http.{Request, Response, URL}
import zio.metrics.Metric
import zio.test.*

import java.net.ConnectException
import java.sql.SQLException

object ReloadingCacheSpec extends ZIOSpecDefault:

  /** A source that fails `failures` times before answering.
    *
    * Counts its calls, which is what the retry rule is actually observable through: how long a
    * load is allowed to keep trying differs by the class of the failure, not by its message.
    */
  private def source(error: => Throwable, failures: Int, calls: Ref[Int]): CacheSource[Vector[String]] =
    new CacheSource[Vector[String]]:
      override def getAll: Task[Vector[String]] =
        calls.updateAndGet(_ + 1).flatMap: attempt =>
          if attempt > failures then ZIO.succeed(Vector("loaded")) else ZIO.fail(error)

  /** Runs an initial load against the test clock.
    *
    * The schedule sleeps between attempts, so nothing happens until the clock is moved. Stepping
    * it rather than jumping keeps the elapsed time the wait is bounded by roughly proportional to
    * the number of attempts made, the way it is in a real process.
    */
  private def load(cacheSource: CacheSource[Vector[String]]): ZIO[Any, Nothing, Exit[Throwable, Vector[String]]] =
    for
      fiber <- ZIO
        .scoped(ReloadingCache.make[Vector[String]](5.minutes).provideSome[Scope](ZLayer.succeed(cacheSource)))
        .flatMap(_.get)
        .fork
      _ <- (TestClock.adjust(500.millis) *> fiber.poll).repeatUntil(_.isDefined)
      exit <- fiber.await
    yield exit

  /** A source whose requests go through `snapshot`, the way a sync client's go through its
    * central transport. Answers with `body` while `up`, fails like an unreachable central
    * otherwise.
    */
  private def syncSource(
      snapshot: ConfigSnapshot,
      up: Ref[Boolean],
      body: Ref[String],
      calls: Ref[Int],
      error: Ref[Throwable],
  ): CacheSource[String] =
    new CacheSource[String]:
      private val request = Request.get(URL.decode("https://central.example/configuration/clients/sync").toOption.get)
      override def getAll: Task[String] =
        ZIO.scoped:
          snapshot.through(request):
            calls.update(_ + 1) *> up.get.flatMap:
              case true => body.get.map(Response.text(_))
              case false => error.get.flatMap(ZIO.fail(_))
          .flatMap(_.body.asString)

  private class InMemoryRepository(records: Ref[Map[String, ConfigSnapshot.Record]]) extends ConfigSnapshot.Repository:
    override def find(key: String): Task[Option[ConfigSnapshot.Record]] = records.get.map(_.get(key))
    override def save(record: ConfigSnapshot.Record): Task[Unit] = records.update(_.updated(record.key, record))

  private case class Fixture(
      snapshot: ConfigSnapshot,
      records: Ref[Map[String, ConfigSnapshot.Record]],
      up: Ref[Boolean],
      body: Ref[String],
      calls: Ref[Int],
      error: Ref[Throwable],
  ):
    val source: CacheSource[String] = syncSource(snapshot, up, body, calls, error)

  private val fixture: UIO[Fixture] =
    for
      records <- Ref.make(Map.empty[String, ConfigSnapshot.Record])
      snapshot <- ConfigSnapshot.make(InMemoryRepository(records), Array.fill[Byte](32)(7))
      up <- Ref.make(true)
      body <- Ref.make("saved")
      calls <- Ref.make(0)
      error <- Ref.make[Throwable](RuntimeException("malformed response"))
    yield Fixture(snapshot, records, up, body, calls, error)

  /** Starts a cache in `scope`, stepping the test clock until the start finishes. */
  private def start(source: CacheSource[String], fromSnapshot: Boolean, scope: Scope): UIO[Exit[Throwable, ReloadingCache[String]]] =
    for
      fiber <- ReloadingCache.make[String](5.minutes, fromSnapshot).provideEnvironment(ZEnvironment(source, scope)).fork
      _ <- (TestClock.adjust(500.millis) *> fiber.poll).repeatUntil(_.isDefined)
      exit <- fiber.await
    yield exit

  private val snapshotAge =
    Metric.gauge("config_snapshot_age_seconds").tagged("cache", Tag[String].tag.toString)

  private val fromSnapshotSuite = suite("from the configuration snapshot")(
    test("is used only after the retry budget, and serves the saved value") {
      for
        f <- fixture
        _ <- f.source.getAll
        _ <- f.up.set(false) *> f.calls.set(0)
        scope <- Scope.make
        exit <- start(f.source, fromSnapshot = true, scope)
        value <- ZIO.fromEither(exit.toEither).flatMap(_.get)
        attempts <- f.calls.get
        _ <- scope.close(Exit.unit)
      yield assertTrue(value == "saved", attempts >= 7)
    },
    test("a cache started after another has fallen back to it does not wait for the source again") {
      for
        f <- fixture
        _ <- f.source.getAll
        _ <- f.error.set(ConnectException("refused")) *> f.up.set(false)
        scope <- Scope.make
        first <- start(f.source, fromSnapshot = true, scope)
        secondCalls <- Ref.make(0)
        before <- Clock.instant
        second <- start(syncSource(f.snapshot, f.up, f.body, secondCalls, f.error), fromSnapshot = true, scope)
        after <- Clock.instant
        value <- ZIO.fromEither(second.toEither).flatMap(_.get)
        _ <- scope.close(Exit.unit)
      yield assertTrue(
        first.isSuccess,
        value == "saved",
        java.time.Duration.between(before, after).toSeconds < 5,
      )
    },
    test("is not used unless asked for") {
      for
        f <- fixture
        _ <- f.source.getAll
        _ <- f.up.set(false)
        scope <- Scope.make
        exit <- start(f.source, fromSnapshot = false, scope)
        _ <- scope.close(Exit.unit)
      yield assertTrue(exit.isFailure)
    },
    test("without a record the start fails with the source's own error") {
      for
        f <- fixture
        _ <- f.up.set(false)
        scope <- Scope.make
        exit <- start(f.source, fromSnapshot = true, scope)
        _ <- scope.close(Exit.unit)
      yield assertTrue(exit.causeOption.flatMap(_.failureOption).map(_.getMessage).contains("malformed response"))
    },
    test("a record that fails verification fails the start") {
      for
        f <- fixture
        _ <- f.source.getAll
        _ <- f.records.update(_.view.mapValues(r => r.copy(body = "forged".getBytes)).toMap)
        _ <- f.up.set(false)
        scope <- Scope.make
        exit <- start(f.source, fromSnapshot = true, scope)
        _ <- scope.close(Exit.unit)
      yield assertTrue(exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[ConfigSnapshot.Rejected]))
    },
    test("the source is tried in the background and replaces the value once it answers; the age is reported until then") {
      for
        f <- fixture
        _ <- f.source.getAll
        _ <- TestClock.adjust(1.hour)
        _ <- f.up.set(false)
        scope <- Scope.make
        exit <- start(f.source, fromSnapshot = true, scope)
        cache <- ZIO.fromEither(exit.toEither)
        _ <- TestClock.adjust(15.seconds)
        ageWhileDown <- snapshotAge.value
        stillSaved <- cache.get
        _ <- f.body.set("fresh") *> f.up.set(true)
        _ <- (TestClock.adjust(1.second) *> cache.get).repeatUntil(_ == "fresh")
        ageWhenLive <- snapshotAge.value
        _ <- scope.close(Exit.unit)
      yield assertTrue(
        stillSaved == "saved",
        ageWhileDown.value >= 3600.0,
        ageWhenLive.value == 0.0,
      )
    },
  ) @@ TestAspect.sequential

  def spec = suite("ReloadingCache")(
    fromSnapshotSuite,
    test("waits for a source that is only not up yet, well past the bounded retry") {
      for
        calls <- Ref.make(0)
        exit <- load(source(ConnectException("Connection refused"), failures = 20, calls))
        attempts <- calls.get
      yield assertTrue(exit == Exit.succeed(Vector("loaded")), attempts == 21)
    },
    test("treats a wrapped connect failure as not up yet") {
      for
        calls <- Ref.make(0)
        exit <- load(source(RuntimeException("request failed", ConnectException("refused")), failures = 20, calls))
        attempts <- calls.get
      yield assertTrue(exit == Exit.succeed(Vector("loaded")), attempts == 21)
    },
    test("treats a connection-class SQL failure as not up yet") {
      for
        calls <- Ref.make(0)
        exit <- load(source(SQLException("connection refused", "08001"), failures = 20, calls))
        attempts <- calls.get
      yield assertTrue(exit == Exit.succeed(Vector("loaded")), attempts == 21)
    },
    test("gives up on a source that never comes up, rather than waiting forever") {
      for
        calls <- Ref.make(0)
        exit <- load(source(ConnectException("Connection refused"), failures = Int.MaxValue, calls))
        attempts <- calls.get
      yield assertTrue(exit.isFailure, attempts > 7)
    },
    test("still fails fast on a failure it cannot classify") {
      for
        calls <- Ref.make(0)
        exit <- load(source(RuntimeException("malformed response"), failures = Int.MaxValue, calls))
        attempts <- calls.get
      yield assertTrue(exit.isFailure, attempts == 7)
    },
    test("an unclassifiable failure that clears is still absorbed") {
      for
        calls <- Ref.make(0)
        exit <- load(source(RuntimeException("malformed response"), failures = 6, calls))
        attempts <- calls.get
      yield assertTrue(exit == Exit.succeed(Vector("loaded")), attempts == 7)
    },
  )
