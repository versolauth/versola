package versola.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.audit.CentralAuditRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

object PostgresAuditEventRepositorySpec extends PostgresSpec, CentralAuditRepositorySpec:

  import CentralAuditRepositorySpec.{oldAt, written}

  private class PostgresFixtures(xa: TransactorZIO) extends CentralAuditRepositorySpec.Fixtures:
    override def countUnpartitioned: Task[Long] =
      xa.connect(sql"SELECT COUNT(*) FROM central_audit_event_default".query[Long].run().head)

    override def dropOldDayPartition: Task[Unit] = PostgresAuditEventRepositorySpec.dropOldDayPartition(xa)

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield CentralAuditRepositorySpec.Env(PostgresAuditEventRepository(xa), PostgresFixtures(xa))

  override def beforeEach(env: CentralAuditRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      // the table refuses TRUNCATE as it refuses DELETE, unless the transaction says it is the service's maintenance
      xa.transact:
        sql"SET LOCAL versola.audit_maintenance = 'on'".update.run()
        sql"TRUNCATE TABLE central_audit_event".update.run()
    }.unit

  private def dropOldDayPartition(xa: TransactorZIO) = xa.connect(sql"DROP TABLE IF EXISTS central_audit_event_20010203".update.run()).unit

  override def implementationCases(env: CentralAuditRepositorySpec.Env) =
    List(
      test("a row cannot be updated, deleted or truncated away, and the service's own prune of the default partition can delete it") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- dropOldDayPartition(xa)
          _ <- env.repository.insertBatch(List(written("00000000-0000-7000-8000-000000000001")))
          updated <- xa.transact(sql"UPDATE central_audit_event SET reason = 'edited'".update.run()).either
          deleted <- xa.transact(sql"DELETE FROM central_audit_event".update.run()).either
          truncated <- xa.transact(sql"TRUNCATE TABLE central_audit_event".update.run()).either
          kept <- xa.connect(sql"SELECT COUNT(*) FROM central_audit_event".query[Long].run().head)
          pruned <- env.repository.pruneDefault(oldAt.plusSeconds(1))
          left <- xa.connect(sql"SELECT COUNT(*) FROM central_audit_event".query[Long].run().head)
        yield assertTrue(
          updated.isLeft,
          deleted.isLeft,
          truncated.isLeft,
          truncated.left.exists(_.getMessage.contains("append-only")),
          updated.left.exists(_.getMessage.contains("append-only")),
          kept == 1L,
          pruned == 1L,
          left == 0L,
        )
      },
    )
