package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.{Instant, ZoneOffset}
import java.util.UUID

object PostgresAntifraudListRepositorySpec extends PostgresSpec, AntifraudListRepositorySpec:

  import AntifraudListRepositorySpec.*

  private class PostgresFixtures(xa: TransactorZIO) extends Fixtures:
    override def stats(tenant: String, prefix: String, minute: Instant, requests: Int): Task[Unit] =
      xa.connect:
        sql"""INSERT INTO af_dest_stats (tenant_id, dest_prefix, minute, requests, sent, suppressed, throttled, provider_errors, verified, wrong_codes)
              VALUES ($tenant, $prefix, ${minute.atOffset(ZoneOffset.UTC)}, $requests, 0, 0, 0, 0, 0, 0)""".update.run()
      .unit

    override def clearStats: Task[Unit] =
      xa.connect(sql"DELETE FROM af_dest_stats".update.run()).unit

    // The id of the transaction that wrote the row version: unchanged for a row the statement left alone.
    override def rowVersions(ids: List[UUID]): Task[Map[UUID, Long]] =
      val keys = ids.toArray
      xa.connect:
        sql"SELECT id, xmin::text::bigint FROM af_list_entry WHERE id = ANY($keys)".query[(UUID, Long)].run().toMap

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield Env(
        repository = PostgresAntifraudListRepository(xa),
        events = PostgresAntifraudEventRepository(xa),
        resets = PostgresAntifraudCounterResetRepository(xa),
        fixtures = PostgresFixtures(xa),
      )

  override def beforeEach(env: Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect:
        sql"DELETE FROM af_list_entry".update.run()
        sql"UPDATE af_list_version SET version = 0, full_copied_at = NULL".update.run()
        sql"DELETE FROM af_counter_reset".update.run()
        // What the preview cases wrote: the events of the hour before `at`, in partitions that may be gone by now.
        sql"DELETE FROM af_event WHERE at >= ${at.minusSeconds(3600).atOffset(ZoneOffset.UTC)}".update.run()
    yield ()

  override def implementationCases(env: Env) =
    List(
      test("every method is under the statement timeout: a lock held on its tables fails it instead of hanging") {
        for
          xa <- ZIO.service[TransactorZIO]
          impatient = PostgresAntifraudListRepository(xa, statementTimeout = 100.millis)
          id = UUID.randomUUID()
          holder <- xa.transact:
            sql"LOCK TABLE af_list_entry, af_list_version, af_counter_reset IN ACCESS EXCLUSIVE MODE".update.run()
            Thread.sleep(4000)
          .fork
          _ <- Live.live(ZIO.sleep(500.millis))
          calls = List(
            "replaceAll" -> impatient.replaceAll(List(entry()), 1, at, at).unit,
            "loadActive" -> impatient.loadActive(at).unit,
            "version" -> impatient.version.unit,
            "copied" -> impatient.copied.unit,
            "applyChanges" -> impatient.applyChanges(List(entry()), Nil, 0, 1).unit,
            "removeExpired" -> impatient.removeExpired(at, 10).unit,
            "addHits" -> impatient.addHits(List(ListHit(id, 1, at))),
            "hits" -> impatient.hits(List(id)).unit,
            "pruneCounterResets" -> impatient.pruneCounterResets(at, 10).unit,
          )
          exits <- ZIO.foreach(calls)((name, call) => call.exit.map(name -> _))
          _ <- holder.join
        yield assertTrue(
          exits.filterNot(_._2.isFailure).map(_._1) == Nil,
          exits.forall(_._2.causeOption.exists(_.squash.getMessage.contains("statement timeout"))),
        )
      },
    )
