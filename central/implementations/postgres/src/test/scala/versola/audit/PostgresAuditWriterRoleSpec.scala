package versola.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.audit.CentralAudit
import versola.util.audit.*
import versola.util.postgres.{AuditWriterPool, AuditWriterRole, PostgresSpec}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID

/** `central_audit_event` (the real table, with the append-only triggers of its migration) written through the insert-only role: what the
  * role writes and is refused, and that the owner's pool still maintains and reads. */
object PostgresAuditWriterRoleSpec extends PostgresSpec:

  private val Role = "versola_audit_writer_central_spec"
  private val Table = CentralAudit.Table

  /** Days no other spec of the module uses, so that a partition made here is this spec's alone. */
  private val partitionedDay = LocalDate.of(2037, 3, 5)
  private val unpartitionedDay = LocalDate.of(2037, 3, 9)
  private val keepEverything = LocalDate.of(2000, 1, 1)

  private def at(day: LocalDate, hour: Int): Instant = day.atTime(hour, 0).toInstant(ZoneOffset.UTC)

  private def event(node: String, user: UUID, at: Instant) =
    AuditEvent(
      id = UUID.randomUUID(),
      at = at,
      tenantId = None,
      source = CentralAudit.Source,
      nodeId = node,
      kind = CentralAudit.admin("tenant", "create"),
      outcome = AuditOutcome.success,
      reason = None,
      severity = AuditSeverity.info,
      actorType = CentralAudit.Actors.Admin,
      actorId = None,
      userId = Some(user),
      clientId = None,
      sessionId = None,
      ip = None,
      userAgent = None,
      traceId = None,
      targetType = None,
      targetId = None,
      details = Json.Obj(),
    )

  private def partitionName(day: LocalDate) = s"${Table}_${day.toString.replace("-", "")}"

  private def failed[A](task: Task[A]): Task[Throwable] = task.flip.mapError(_ => IllegalStateException("expected a failure"))

  private def count(xa: TransactorZIO, relation: String, node: String) =
    xa.connect(sql"SELECT COUNT(*) FROM ${SqlLiteral(relation)} WHERE node_id = $node".query[Long].run().head)

  private def withWriter[A](
      use: (TransactorZIO, PostgresAuditEventRepository, PostgresAuditEventRepository, TransactorZIO) => Task[A],
  ): ZIO[TransactorZIO, Throwable, A] =
    ZIO.serviceWithZIO[TransactorZIO]: owner =>
      ZIO.scoped:
        for
          _ <- AuditWriterRole.scoped(owner, Role, "public", List(Table), keyColumns = true)
          pool <- AuditWriterRole.pool(Role, AuditWriterRole.Password, CentralAudit.Source, Table).provideSomeLayer[Scope](PostgresSpec.processConfig)
          writerXa <- ZIO.fromOption(pool.transactor).orElseFail(IllegalStateException("no pool"))
          ownerRepository = PostgresAuditEventRepository(owner)
          writerRepository = PostgresAuditEventRepository(owner, Some(writerXa))
          _ <- ownerRepository.maintainPartitions(from = partitionedDay, days = 1, keepFrom = keepEverything)
          result <- use(owner, ownerRepository, writerRepository, writerXa).ensuring(cleanUp(owner).orDie)
        yield result

  private def cleanUp(owner: TransactorZIO): Task[Unit] =
    owner.transact:
      sql"SET LOCAL versola.audit_maintenance = 'on'".update.run()
      sql"DELETE FROM central_audit_event WHERE node_id LIKE 'writer-role-spec-%'".update.run()
      sql"DROP TABLE IF EXISTS ${SqlLiteral(partitionName(partitionedDay))}".update.run()
      sql"DROP TABLE IF EXISTS ${SqlLiteral(partitionName(partitionedDay.plusDays(30)))}".update.run()
      ()

  def spec = suite("central's audit writer as an insert-only role")(
    test("a batch is written into a day's partition and, for a day without one, into the default partition") {
      val node = s"writer-role-spec-${UUID.randomUUID()}"
      val user = UUID.randomUUID()
      withWriter((owner, _, writer, _) =>
        for
          _ <- writer.insertBatch(
            List(event(node, user, at(partitionedDay, 10)), event(node, user, at(partitionedDay, 11)), event(node, user, at(unpartitionedDay, 10))),
          )
          day <- count(owner, partitionName(partitionedDay), node)
          default <- count(owner, s"${Table}_default", node)
        yield assertTrue(day == 2L, default == 1L),
      )
    },
    test("an event offered again is written once") {
      val node = s"writer-role-spec-${UUID.randomUUID()}"
      val e = event(node, UUID.randomUUID(), at(partitionedDay, 10))
      withWriter((owner, _, writer, _) =>
        for
          _ <- writer.insertBatch(List(e))
          _ <- writer.insertBatch(List(e, e))
          rows <- count(owner, Table, node)
        yield assertTrue(rows == 1L),
      )
    },
    test("the role cannot update, delete, truncate, detach or drop the table or its partitions, with the maintenance mark set") {
      val node = s"writer-role-spec-${UUID.randomUUID()}"
      withWriter((owner, _, writer, writerXa) =>
        for
          _ <- writer.insertBatch(
            List(event(node, UUID.randomUUID(), at(partitionedDay, 10)), event(node, UUID.randomUUID(), at(unpartitionedDay, 10))),
          )
          allowed <-
            AuditWriterRole.allowed(writerXa, AuditWriterRole.refused(Table, partitionName(partitionedDay)) :+ s"DELETE FROM ${Table}_default")
          rows <- count(owner, Table, node)
        yield assertTrue(allowed == Nil, rows == 2L),
      )
    },
    test("the owner's pool still maintains the partitions, prunes the default partition under the mark, and reads for the report") {
      val node = s"writer-role-spec-${UUID.randomUUID()}"
      val user = UUID.randomUUID()
      withWriter((owner, ownerRepository, writer, _) =>
        for
          _ <- writer.insertBatch(List(event(node, user, at(partitionedDay, 10)), event(node, user, at(unpartitionedDay, 10))))
          found <- ownerRepository.userEvents(user, at(partitionedDay, 0), at(unpartitionedDay, 23), before = None, limit = 10)
          ownership <- ownerRepository.ownership
          pruned <- ownerRepository.pruneDefault(at(unpartitionedDay, 11))
          left <- count(owner, Table, node)
          report <- ownerRepository.maintainPartitions(from = partitionedDay.plusDays(30), days = 1, keepFrom = partitionedDay.plusDays(1))
        yield assertTrue(found.size == 2, ownership.mayAlter, pruned >= 1L, left == 1L, report.created == 1, report.dropped >= 1),
      )
    },
    test("a repository without a writer pool inserts as the service's own role, as before") {
      val node = s"writer-role-spec-${UUID.randomUUID()}"
      withWriter((owner, ownerRepository, _, _) =>
        for
          _ <- ownerRepository.insertBatch(List(event(node, UUID.randomUUID(), at(partitionedDay, 10))))
          rows <- count(owner, Table, node)
        yield assertTrue(rows == 1L),
      )
    },
    test("a grant taken away after the start fails the write with the role's name for it") {
      val node = s"writer-role-spec-${UUID.randomUUID()}"
      withWriter((owner, _, writer, _) =>
        for
          _ <- owner.connect(sql"REVOKE INSERT ON central_audit_event FROM ${SqlLiteral(Role)}".update.run())
          failure <- failed(writer.insertBatch(List(event(node, UUID.randomUUID(), at(partitionedDay, 10)))))
          rows <- count(owner, Table, node)
        yield assertTrue(failure.isInstanceOf[AuditWriterPool.AuditWriterAccessDenied], rows == 0L),
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
