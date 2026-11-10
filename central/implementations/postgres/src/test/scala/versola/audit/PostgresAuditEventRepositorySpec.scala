package versola.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.audit.{CentralAudit, CentralAuditRepository, CentralAuditRepositorySpec}
import versola.util.audit.*
import versola.util.postgres.PostgresSpec
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object PostgresAuditEventRepositorySpec extends PostgresSpec, CentralAuditRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield CentralAuditRepositorySpec.Env(PostgresAuditEventRepository(xa))

  override def beforeEach(env: CentralAuditRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      // the table refuses TRUNCATE as it refuses DELETE, unless the transaction says it is the service's maintenance
      xa.transact:
        sql"SET LOCAL versola.audit_maintenance = 'on'".update.run()
        sql"TRUNCATE TABLE central_audit_event".update.run()
    }.unit

  /** A day no run of this spec or of the service has a partition for, so that its rows are in the default partition. */
  private val oldDay = LocalDate.of(2001, 2, 3)
  private val oldAt = Instant.parse("2001-02-03T10:00:00Z")

  private def written(id: String) = AuditEvent(
    id = UUID.fromString(id),
    at = oldAt,
    tenantId = None,
    source = CentralAudit.Source,
    nodeId = "node-1",
    kind = CentralAudit.admin("tenant", "create"),
    outcome = AuditOutcome.success,
    reason = None,
    severity = AuditSeverity.info,
    actorType = CentralAudit.Actors.Admin,
    actorId = None,
    userId = Some(UUID.fromString("00000000-0000-0000-0000-000000000001")),
    clientId = None,
    sessionId = None,
    ip = None,
    userAgent = None,
    traceId = None,
    targetType = None,
    targetId = None,
    details = Json.Obj(),
  )

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
      test("a day whose rows sit in the default partition is reported and left alone, and is attached once they are pruned") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- dropOldDayPartition(xa)
          _ <- env.repository.insertBatch(List(written("00000000-0000-7000-8000-000000000002")))
          held <- env.repository.maintainPartitions(oldDay, 1, LocalDate.of(2000, 1, 1))
          stillThere <- xa.connect(sql"SELECT COUNT(*) FROM central_audit_event_default".query[Long].run().head)
          pruned <- env.repository.pruneDefault(oldAt.plusSeconds(1))
          attached <- env.repository.maintainPartitions(oldDay, 1, LocalDate.of(2000, 1, 1))
          ahead <- env.repository.partitionDaysAhead(oldDay.minusDays(1))
          _ <- dropOldDayPartition(xa)
        yield assertTrue(
          // never moved: an insert-only replication of the table would see the row twice
          held.daysLeftInDefault == List(oldDay),
          held.created == 0,
          held.movedFromDefault == 0L,
          stillThere == 1L,
          pruned == 1L,
          attached.daysLeftInDefault.isEmpty,
          attached.created == 1,
          ahead >= 1,
        )
      },
      test("the rows of a partner are those whose details name it, newest first, narrowed by actor, kind and text, and paged by key") {
        def about(id: String, partner: String, actor: String, kind: String, target: String, at: Instant) =
          written(id).copy(
            at = at,
            kind = AuditKind(kind),
            actorId = Some(actor),
            targetId = Some(target),
            details = Json.Obj("partnerId" -> Json.Str(partner)),
          )
        val first = about("00000000-0000-7000-8000-000000000011", "acme", "partner-staff:alice", "partner.client.create", "acme-app", oldAt)
        val second =
          about("00000000-0000-7000-8000-000000000012", "acme", "partner-staff:bob", "partner.client.update", "acme-web", oldAt.plusSeconds(60))
        val third =
          about("00000000-0000-7000-8000-000000000013", "acme", "partner-staff:alice", "partner.client.update", "acme-app", oldAt.plusSeconds(120))
        val foreign = about(
          "00000000-0000-7000-8000-000000000014",
          "globex",
          "partner-staff:carol",
          "partner.client.create",
          "globex-app",
          oldAt.plusSeconds(180),
        )
        val unrelated = written("00000000-0000-7000-8000-000000000015")
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- dropOldDayPartition(xa)
          _ <- env.repository.insertBatch(List(first, second, third, foreign, unrelated))
          all <- env.repository.partnerEvents("acme", None, None, None, None, 10)
          byActor <- env.repository.partnerEvents("acme", None, Some("partner-staff:alice"), None, None, 10)
          byKind <- env.repository.partnerEvents("acme", None, None, Some("partner.client.create"), None, 10)
          byText <- env.repository.partnerEvents("acme", None, None, None, Some("acme-web"), 10)
          page <- env.repository.partnerEvents("acme", None, None, None, None, 2)
          next <- env.repository.partnerEvents("acme", Some(CentralAuditRepository.Key(page.last.at, page.last.id)), None, None, None, 2)
          nobody <- env.repository.partnerEvents("initech", None, None, None, None, 10)
          _ <- dropOldDayPartition(xa)
        yield assertTrue(
          all.map(_.id) == List(third.id, second.id, first.id),
          byActor.map(_.id) == List(third.id, first.id),
          byKind.map(_.id) == List(first.id),
          byText.map(_.id) == List(second.id),
          page.map(_.id) == List(third.id, second.id),
          next.map(_.id) == List(first.id),
          nobody.isEmpty,
        )
      },
      test("the service is the owner of the table, so that it may maintain the partitions") {
        for ownership <- env.repository.ownership
        yield assertTrue(ownership.mayAlter, ownership.owner == ownership.role)
      },
    )
