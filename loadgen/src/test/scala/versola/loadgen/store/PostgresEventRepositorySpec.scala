package versola.loadgen.store

import com.augustnagro.magnum.{DbCodec, sql}
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

/** Carries the transactor as well as the repository: `vu_events` has no read method to check a
  * write with, by design, so the spec reads the table itself.
  */
final case class EventEnv(repository: EventRepository, xa: TransactorZIO)

/** `vu_events` against a real Postgres. The table has no primary key, no index and no read
  * method (dev spec §6), so the only thing to establish is that the forensic sample lands in
  * full and with every column in the right place -- the sample is read by hand, once, long
  * after the campaign, and a column silently written into the wrong one is not recoverable then.
  */
object PostgresEventRepositorySpec extends LoadgenPostgresSpec, DatabaseSpecBase[EventEnv], StoreCodecs:

  private given DbCodec[EventRow] = DbCodec.derived

  private val now = Instant.parse("2026-01-01T12:00:00Z")

  private def event(secondsIn: Int, step: String): EventRow =
    EventRow(
      at = now.plusSeconds(secondsIn),
      userId = 7,
      scenario = "mobile-login",
      step = step,
      outcome = "ok",
      latencyMs = 12 + secondsIn,
    )

  private def stored(env: EventEnv): Task[Vector[EventRow]] =
    env.xa.connect:
      sql"""
        SELECT at, user_id, scenario, step, outcome, latency_ms FROM vu_events ORDER BY at
      """.query[EventRow].run()

  override lazy val environment =
    ZLayer:
      ZIO.serviceWith[TransactorZIO](xa => EventEnv(PostgresEventRepository(xa), xa))

  override def beforeEach(env: EventEnv) =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(sql"TRUNCATE TABLE vu_events".update.run()).unit

  override def testCases(env: EventEnv) = List(
    test("appendAll writes every sampled step, with every column where it belongs") {
      val sample = Chunk(event(0, "authorize"), event(1, "submit-otp"), event(2, "exchange-code"))
      for
        _     <- env.repository.appendAll(sample)
        found <- stored(env)
      yield assertTrue(found == sample.toVector)
    },
    test("appendAll appends rather than replaces, so two flushes both survive") {
      for
        _     <- env.repository.appendAll(Chunk(event(0, "authorize")))
        _     <- env.repository.appendAll(Chunk(event(1, "submit-otp")))
        found <- stored(env)
      yield assertTrue(found.map(_.step) == Vector("authorize", "submit-otp"))
    },
    test("appendAll keeps a duplicate, because the sample is a log and not a state") {
      for
        _     <- env.repository.appendAll(Chunk(event(0, "authorize"), event(0, "authorize")))
        found <- stored(env)
      yield assertTrue(found.size == 2)
    },
    test("appendAll on an empty chunk is a no-op, not an empty round trip") {
      for
        _     <- env.repository.appendAll(Chunk.empty)
        found <- stored(env)
      yield assertTrue(found.isEmpty)
    },
  )
