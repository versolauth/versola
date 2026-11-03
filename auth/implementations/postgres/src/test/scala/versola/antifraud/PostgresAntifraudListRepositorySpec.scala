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
        sql"DELETE FROM af_list_audit".update.run()
        sql"DELETE FROM af_counter_reset".update.run()
        // What the preview cases wrote: the events of the hour before `at`, in partitions that may be gone by now.
        sql"DELETE FROM af_event WHERE at >= ${at.minusSeconds(3600).atOffset(ZoneOffset.UTC)}".update.run()
    yield ()

  override def implementationCases(env: Env) =
    val repository = env.repository
    List(
      test("every method is under the statement timeout: a lock held on its tables fails it instead of hanging") {
        for
          xa <- ZIO.service[TransactorZIO]
          impatient = PostgresAntifraudListRepository(xa, statementTimeout = 100.millis)
          id = UUID.randomUUID()
          holder <- xa.transact:
            sql"LOCK TABLE af_list_entry, af_list_audit, af_list_version IN ACCESS EXCLUSIVE MODE".update.run()
            Thread.sleep(4000)
          .fork
          _ <- Live.live(ZIO.sleep(500.millis))
          calls = List(
            "upsert" -> impatient.upsert(entry(), "x", at).unit,
            "delete" -> impatient.delete(id, "x", at).unit,
            "find" -> impatient.find(id).unit,
            "list" -> impatient.list(AntifraudListFilter.all, 10, 0).unit,
            "loadActive" -> impatient.loadActive(at).unit,
            "version" -> impatient.version.unit,
            "addHits" -> impatient.addHits(List(ListHit(id, 1, at))),
            "purgeExpired" -> impatient.purgeExpired(at, 10).unit,
            "pruneAudit" -> impatient.pruneAudit(at, 10).unit,
            "audit of all" -> impatient.audit(None, 10, None).unit,
            "audit of one" -> impatient.audit(Some(id), 10, None).unit,
          )
          exits <- ZIO.foreach(calls)((name, call) => call.exit.map(name -> _))
          _ <- holder.join
        yield assertTrue(
          exits.filterNot(_._2.isFailure).map(_._1) == Nil,
          exits.forall(_._2.causeOption.exists(_.squash.getMessage.contains("statement timeout"))),
        )
      },
      test("a row of a kind this version does not know is stored, and skipped when the cache loads") {
        for
          xa <- ZIO.service[TransactorZIO]
          now <- Clock.instant
          _ <- xa.connect(
            sql"""INSERT INTO af_list_entry (id, type, value_type, value, source, created_by, created_at)
                VALUES (${UUID.randomUUID()}, 'ban', 'ip', 'x', 's', 'c', now()),
                       (${UUID.randomUUID()}, 'deny', 'fingerprint', 'y', 's', 'c', now()),
                       (${UUID.randomUUID()}, 'deny', 'ip', '203.0.113.7', 's', 'c', now())""".update.run(),
          )
          loaded <- repository.loadActive(now)
        yield assertTrue(loaded.map(_.value) == List("203.0.113.7"))
      },
    )
