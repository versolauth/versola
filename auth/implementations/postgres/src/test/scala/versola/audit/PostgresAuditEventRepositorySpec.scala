package versola.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.audit.AuditEventRepositorySpec.*
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

/** Runs [[AuditEventRepositorySpec]] against `audit_event`, and adds what only this storage promises: the trigger that refuses an
  * `UPDATE` or `DELETE` that is not the service's own maintenance, on the table and on every partition, those attached later included. */
object PostgresAuditEventRepositorySpec extends PostgresSpec, AuditEventRepositorySpec:

  private class PostgresFixtures(xa: TransactorZIO) extends Fixtures:
    override def countEvents: Task[Long] =
      xa.connect(sql"SELECT COUNT(*) FROM audit_event".query[Long].run().head)

    override def countUnpartitioned: Task[Long] =
      xa.connect(sql"SELECT COUNT(*) FROM audit_event_default".query[Long].run().head)

  /** The tables of the days that are partitions of the log, not the default partition. */
  private def partitionNames(xa: TransactorZIO): Task[List[String]] =
    xa.connect:
      sql"""SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'audit_event' AND c.relname <> 'audit_event_default'
            ORDER BY c.relname""".query[String].run().toList

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield
        val repository = PostgresAuditEventRepository(xa, PostgresAuditEventRepository.partitions)
        Env(repository, repository, PostgresFixtures(xa))

  override def beforeEach(env: Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      // Everything older than a date far ahead goes, partitions this suite made included.
      _ <- env.repository.maintainPartitions(from = farFuture, days = 0, keepFrom = farFuture.plusYears(1))
      // TRUNCATE fires no row trigger: the one way a test can empty the default partition without the maintenance mark.
      _ <- xa.connect(sql"TRUNCATE audit_event_default".update.run())
    yield ()

  override def implementationCases(env: Env) =
    val repository = env.repository
    List(
      test("a partition is a table named for its day") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- repository.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30))
          names <- partitionNames(xa)
        yield assertTrue(names == List("audit_event_20261021", "audit_event_20261022", "audit_event_20261023"))
      },
      test("refuses an UPDATE and a DELETE of a row, on a partition made by the service as on the table, unless marked as maintenance") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          row = event(utc(today.atTime(10, 0)), user = Some(user1))
          _ <- repository.insertBatch(List(row))
          update <- xa.connect(sql"UPDATE audit_event SET reason = 'x'".update.run()).exit
          delete <- xa.connect(sql"DELETE FROM audit_event".update.run()).exit
          onPartition <- xa.connect(sql"DELETE FROM audit_event_20261021".update.run()).exit
          rows <- env.fixtures.countEvents
          // The retention drops a partition, which is DDL and fires no row trigger.
          dropped <- repository.maintainPartitions(from = today.plusDays(5), days = 1, keepFrom = today.plusDays(1))
          left <- env.fixtures.countEvents
        yield assertTrue(
          update.isFailure,
          delete.isFailure,
          onPartition.isFailure,
          rows == 1L,
          dropped.dropped == 1,
          left == 0L,
        )
      },
      test("the refusal says the log is append-only, and a transaction marked as maintenance may delete") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          _ <- repository.insertBatch(List(event(utc(today.atTime(10, 0)), user = Some(user1))))
          refused <- xa.connect(sql"DELETE FROM audit_event".update.run()).flip.map(_.getMessage)
          marked <- xa.transact:
            sql"SET LOCAL versola.audit_maintenance = 'on'".update.run()
            sql"DELETE FROM audit_event".update.run()
          unmarked <- xa.transact:
            sql"SET LOCAL versola.audit_maintenance = 'off'".update.run()
            sql"DELETE FROM audit_event".update.run()
          .exit
        yield assertTrue(refused.contains("audit_event is append-only"), marked == 1, unmarked.isSuccess)
      },
    )
