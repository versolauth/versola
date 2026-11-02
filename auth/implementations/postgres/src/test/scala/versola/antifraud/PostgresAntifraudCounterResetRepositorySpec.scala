package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object PostgresAntifraudCounterResetRepositorySpec extends PostgresSpec:

  private val at = Instant.parse("2026-11-02T10:00:00Z")

  private val clean: ZIO[TransactorZIO, Throwable, PostgresAntifraudCounterResetRepository] =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"DELETE FROM af_counter_reset".update.run())
    yield PostgresAntifraudCounterResetRepository(xa)

  private val whole = CounterReset.Resolved(CounterScope.all, None, None, None)

  def spec = suite("PostgresAntifraudCounterResetRepository")(
    test("an empty log is at generation 0") {
      for
        repository <- clean
        latest <- repository.latest
        since <- repository.since(0, 10)
      yield assertTrue(latest == 0L, since.isEmpty)
    },
    test("a reset gets the next generation, and is read back whole, oldest first, from any generation") {
      val phone = CounterReset.Resolved(CounterScope.velocity, Some(CounterValueType.phone), Some("hash"), Some("491512"))
      for
        repository <- clean
        first <- repository.record(whole, "alice", at)
        second <- repository.record(phone, "bob", at.plusSeconds(1))
        latest <- repository.latest
        all <- repository.since(0, 10)
        afterFirst <- repository.since(first, 10)
        limited <- repository.since(0, 1)
      yield assertTrue(
        first == 1L,
        second == 2L,
        latest == 2L,
        all == List(
          CounterReset(1, CounterScope.all, None, None, None, "alice", at),
          CounterReset(2, CounterScope.velocity, Some(CounterValueType.phone), Some("hash"), Some("491512"), "bob", at.plusSeconds(1)),
        ),
        afterFirst.map(_.generation) == List(2L),
        limited.map(_.generation) == List(1L),
      )
    },
    test("resets made at once get one generation each, with no gap") {
      for
        repository <- clean
        generations <- ZIO.foreachPar(1 to 12)(i => repository.record(whole, s"user-$i", at))
        read <- repository.since(0, 100)
      yield assertTrue(generations.toSet == (1L to 12L).toSet, read.map(_.generation) == (1L to 12L).toList)
    },
    test("a row of a scope this version does not know is skipped, not fatal") {
      for
        repository <- clean
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.record(whole, "alice", at)
        _ <- xa.connect(
          sql"INSERT INTO af_counter_reset (generation, scope, requested_by, at) VALUES (2, 'future', 'x', now())".update.run(),
        )
        read <- repository.since(0, 10)
      yield assertTrue(read.map(_.generation) == List(1L))
    },
    test("it is under the statement timeout") {
      for
        _ <- clean
        xa <- ZIO.service[TransactorZIO]
        impatient = PostgresAntifraudCounterResetRepository(xa, statementTimeout = 100.millis)
        holder <- xa.transact:
          sql"LOCK TABLE af_counter_reset IN ACCESS EXCLUSIVE MODE".update.run()
          Thread.sleep(2000)
        .fork
        _ <- Live.live(ZIO.sleep(400.millis))
        recorded <- impatient.record(whole, "x", at).exit
        latest <- impatient.latest.exit
        _ <- holder.join
      yield assertTrue(recorded.isFailure, latest.isFailure)
    },
  ) @@ TestAspect.sequential
