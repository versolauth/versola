package versola.edge.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

/** Conformance suite for any [[RevocationCursorRepository]]: what a storage has to promise for the pods of an edge to
  * share one feed. `PostgresRevocationCursorRepositorySpec` in `edge-postgres-impl` binds it to Postgres.
  */
trait RevocationCursorRepositorySpec extends DatabaseSpecBase[RevocationCursorRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val source = "https://auth-mtls.example.com"
  private val lease = 30.seconds

  override def testCases(env: RevocationCursorRepositorySpec.Env) =
    List(
      test("a seeded source reads from the beginning") {
        for
          _ <- env.repository.seed(source)
          cursor <- env.repository.acquire(source, "pod-a", lease)
        yield assertTrue(cursor.contains(""))
      },
      test("seeding again leaves the cursor where it was") {
        for
          _ <- env.repository.seed(source)
          _ <- env.repository.acquire(source, "pod-a", lease)
          _ <- env.repository.advance(source, "pod-a", "cursor-1", lease)
          _ <- env.repository.release(source, "pod-a")
          _ <- env.repository.seed(source)
          cursor <- env.repository.acquire(source, "pod-b", lease)
        yield assertTrue(cursor.contains("cursor-1"))
      },
      test("a source with no row cannot be acquired") {
        for cursor <- env.repository.acquire("https://unknown.example.com", "pod-a", lease)
        yield assertTrue(cursor.isEmpty)
      },
      test("a held lease is not given to another pod") {
        for
          _ <- env.repository.seed(source)
          first <- env.repository.acquire(source, "pod-a", lease)
          second <- env.repository.acquire(source, "pod-b", lease)
          // the holder asking again does not get it either: the pod ticks only after releasing
          again <- env.repository.acquire(source, "pod-a", lease)
        yield assertTrue(first.isDefined, second.isEmpty, again.isEmpty)
      },
      test("exactly one of many contenders gets the lease") {
        for
          _ <- env.repository.seed(source)
          results <- ZIO.foreachPar((1 to 20).toList)(n => env.repository.acquire(source, s"pod-$n", lease))
        yield assertTrue(results.count(_.isDefined) == 1)
      },
      test("an expired lease is taken over by another pod, which reads from the cursor the first left") {
        for
          _ <- env.repository.seed(source)
          _ <- env.repository.acquire(source, "pod-a", lease)
          _ <- env.repository.advance(source, "pod-a", "cursor-1", lease)
          _ <- env.fixtures.expireLease(source)
          taken <- env.repository.acquire(source, "pod-b", lease)
        yield assertTrue(taken.contains("cursor-1"))
      },
      test("the lease runs for the length asked, measured on the database's clock") {
        for
          _ <- env.repository.seed(source)
          _ <- env.repository.acquire(source, "pod-a", lease)
          remaining <- env.fixtures.leaseRemaining(source)
        yield assertTrue(remaining > Duration.Zero, remaining <= lease, remaining > lease.minus(5.seconds))
      },
      test("a lease that has run out is not extended or advanced by the pod that held it") {
        for
          _ <- env.repository.seed(source)
          _ <- env.repository.acquire(source, "pod-a", lease)
          _ <- env.fixtures.expireLease(source)
          renewed <- env.repository.renew(source, "pod-a", lease)
          advanced <- env.repository.advance(source, "pod-a", "cursor-1", lease)
          cursor <- env.fixtures.cursorOf(source)
        yield assertTrue(!renewed, !advanced, cursor.contains(""))
      },
      test("a pod that is not the holder cannot move the cursor, extend the lease or give it back") {
        for
          _ <- env.repository.seed(source)
          _ <- env.repository.acquire(source, "pod-a", lease)
          advanced <- env.repository.advance(source, "pod-b", "cursor-b", lease)
          renewed <- env.repository.renew(source, "pod-b", lease)
          _ <- env.repository.release(source, "pod-b")
          stillHeld <- env.repository.acquire(source, "pod-c", lease)
          cursor <- env.fixtures.cursorOf(source)
        yield assertTrue(!advanced, !renewed, stillHeld.isEmpty, cursor.contains(""))
      },
      test("the holder advances the cursor and keeps the lease while it works") {
        for
          _ <- env.repository.seed(source)
          _ <- env.repository.acquire(source, "pod-a", 2.seconds)
          advanced <- env.repository.advance(source, "pod-a", "cursor-1", lease)
          renewed <- env.repository.renew(source, "pod-a", lease)
          remaining <- env.fixtures.leaseRemaining(source)
          cursor <- env.fixtures.cursorOf(source)
        yield assertTrue(advanced, renewed, remaining > 10.seconds, cursor.contains("cursor-1"))
      },
      test("a released lease can be taken at once, and keeps the cursor") {
        for
          _ <- env.repository.seed(source)
          _ <- env.repository.acquire(source, "pod-a", lease)
          _ <- env.repository.advance(source, "pod-a", "cursor-1", lease)
          _ <- env.repository.release(source, "pod-a")
          taken <- env.repository.acquire(source, "pod-b", lease)
        yield assertTrue(taken.contains("cursor-1"))
      },
      test("a cycle that read to the end restarts the lag, the same figure for any pod asking, and gives the lease back") {
        for
          _ <- env.repository.seed(source)
          _ <- env.fixtures.ageLastRead(source, 120.seconds)
          before <- env.repository.lagSeconds(source)
          _ <- env.repository.acquire(source, "pod-a", lease)
          _ <- env.repository.completed(source, "pod-a")
          after <- env.repository.lagSeconds(source)
          taken <- env.repository.acquire(source, "pod-b", lease)
        yield assertTrue(before.exists(_ >= 120), after.exists(_ < 5), taken.isDefined)
      },
      test("a cycle that did not finish leaves the lag rising, and moving the cursor is not a finished cycle") {
        for
          _ <- env.repository.seed(source)
          _ <- env.fixtures.ageLastRead(source, 120.seconds)
          _ <- env.repository.acquire(source, "pod-a", lease)
          _ <- env.repository.advance(source, "pod-a", "cursor-1", lease)
          _ <- env.repository.release(source, "pod-a")
          lag <- env.repository.lagSeconds(source)
        yield assertTrue(lag.exists(_ >= 120))
      },
      test("only the holder can record a finished cycle") {
        for
          _ <- env.repository.seed(source)
          _ <- env.fixtures.ageLastRead(source, 120.seconds)
          _ <- env.repository.acquire(source, "pod-a", lease)
          _ <- env.repository.completed(source, "pod-b")
          lag <- env.repository.lagSeconds(source)
          stillHeld <- env.repository.acquire(source, "pod-c", lease)
        yield assertTrue(lag.exists(_ >= 120), stillHeld.isEmpty)
      },
      test("the lag of a source with no row is unknown") {
        for lag <- env.repository.lagSeconds("https://unknown.example.com")
        yield assertTrue(lag.isEmpty)
      },
      test("sources are independent of each other") {
        val other = "https://auth-2-mtls.example.com"
        for
          _ <- env.repository.seed(source)
          _ <- env.repository.seed(other)
          first <- env.repository.acquire(source, "pod-a", lease)
          second <- env.repository.acquire(other, "pod-a", lease)
        yield assertTrue(first.isDefined, second.isDefined)
      },
    )

object RevocationCursorRepositorySpec:
  /** What the storage does that the cases need and the repository does not offer: making a lease run out without
    * waiting for it, and reading a row back. */
  trait Fixtures:
    def expireLease(source: String): Task[Unit]

    /** Moves the record of the last full read back by `by`, as time would have. */
    def ageLastRead(source: String, by: Duration): Task[Unit]
    def cursorOf(source: String): Task[Option[String]]

    /** Time left on the lease by the database's own clock, negative once it has passed. */
    def leaseRemaining(source: String): Task[Duration]

  case class Env(repository: RevocationCursorRepository, fixtures: Fixtures)
