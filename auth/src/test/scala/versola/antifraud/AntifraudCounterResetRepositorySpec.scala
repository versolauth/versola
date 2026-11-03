package versola.antifraud

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

/** What every [[AntifraudCounterResetRepository]] promises: generations count from 1 with no gap, whoever records at once;
  * a reset is read back whole, oldest first, from any generation and in batches; an empty log stands at 0. */
trait AntifraudCounterResetRepositorySpec extends DatabaseSpecBase[AntifraudCounterResetRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import AntifraudCounterResetRepositorySpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    val repository = env.repository
    List(
      test("an empty log is at generation 0") {
        for
          latest <- repository.latest
          since <- repository.since(0, 10)
        yield assertTrue(latest == 0L, since.isEmpty)
      },
      test("a reset gets the next generation, and is read back whole, oldest first, from any generation") {
        val phone = CounterReset.Resolved(CounterScope.velocity, Some(CounterValueType.phone), Some("hash"), Some("491512"))
        for
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
          generations <- ZIO.foreachPar(1 to 12)(i => repository.record(whole, s"user-$i", at))
          read <- repository.since(0, 100)
        yield assertTrue(generations.toSet == (1L to 12L).toSet, read.map(_.generation) == (1L to 12L).toList)
      },
    )

object AntifraudCounterResetRepositorySpec:
  case class Env(repository: AntifraudCounterResetRepository)

  val at: Instant = Instant.parse("2026-11-02T10:00:00Z")
  val whole: CounterReset.Resolved = CounterReset.Resolved(CounterScope.all, None, None, None)
