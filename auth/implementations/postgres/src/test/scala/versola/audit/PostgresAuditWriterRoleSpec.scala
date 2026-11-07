package versola.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.SecureRandom
import versola.util.audit.*
import versola.util.http.NoopTracing
import versola.util.postgres.{AuditWriterPool, AuditWriterRole, PostgresSpec}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID

/** `auth_audit_event` (the real table, with the append-only triggers of its migration) written through the insert-only role: what the
  * role writes and is refused, that the owner's pool still maintains and reads, and what the audit writer says when the database refuses
  * the role. */
object PostgresAuditWriterRoleSpec extends PostgresSpec:

  private val Role = "versola_audit_writer_auth_spec"
  private val Table = "auth_audit_event"

  /** Days no other spec of the module uses, so that a partition made here is this spec's alone. */
  private val partitionedDay = LocalDate.of(2037, 3, 5)
  private val unpartitionedDay = LocalDate.of(2037, 3, 9)
  private val keepEverything = LocalDate.of(2000, 1, 1)

  private def at(day: LocalDate, hour: Int) = day.atTime(hour, 0).toInstant(java.time.ZoneOffset.UTC)

  private def event(node: String, user: UUID, at: Instant) =
    AuditEvent(
      id = UUID.randomUUID(),
      at = at,
      tenantId = None,
      source = AuthAudit.source,
      nodeId = node,
      kind = AuthAuditKinds.LoginStep,
      outcome = AuditOutcome.success,
      reason = None,
      severity = AuditSeverity.info,
      actorType = ActorType.user,
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

  /** The owner's repository and the writer's, on the owner's pool and on the pool of the role; the partition of [[partitionedDay]] made. */
  private def withWriter[A](keyColumns: Boolean = true)(
      use: (TransactorZIO, PostgresAuditEventRepository, PostgresAuditEventRepository, TransactorZIO) => Task[A],
  ): ZIO[TransactorZIO, Throwable, A] =
    ZIO.serviceWithZIO[TransactorZIO]: owner =>
      ZIO.scoped:
        for
          _ <- AuditWriterRole.scoped(owner, Role, "public", List(Table), keyColumns)
          pool <- AuditWriterRole.pool(Role, AuditWriterRole.Password, AuthAudit.source, Table).provideSomeLayer[Scope](PostgresSpec.processConfig)
          writerXa <- ZIO.fromOption(pool.transactor).orElseFail(IllegalStateException("no pool"))
          partitions = PostgresAuditEventRepository.partitions
          ownerRepository = PostgresAuditEventRepository(owner, partitions)
          writerRepository = PostgresAuditEventRepository(owner, partitions, writer = Some(writerXa))
          _ <- ownerRepository.maintainPartitions(from = partitionedDay, days = 1, keepFrom = keepEverything)
          result <- use(owner, ownerRepository, writerRepository, writerXa).ensuring(cleanUp(owner).orDie)
        yield result

  private def cleanUp(owner: TransactorZIO): Task[Unit] =
    owner.transact:
      sql"SET LOCAL versola.audit_maintenance = 'on'".update.run()
      sql"DELETE FROM auth_audit_event WHERE node_id LIKE 'writer-role-spec-%'".update.run()
      sql"DROP TABLE IF EXISTS ${SqlLiteral(partitionName(partitionedDay))}".update.run()
      sql"DROP TABLE IF EXISTS ${SqlLiteral(partitionName(partitionedDay.plusDays(30)))}".update.run()
      ()

  private val settings = AuditEvents.Settings(retention = 7.days, overflow = AuditEvents.Overflow.drop, blockTimeout = 1.second)

  private val draft = AuditDraft(
    kind = AuthAuditKinds.LoginStep,
    outcome = AuditOutcome.failure,
    reason = Some("writer_role_spec"),
    actor = None,
    userId = None,
    target = None,
    details = AuditDetails.empty,
  )

  def spec = suite("auth's audit writer as an insert-only role")(
    test("a batch is written into a day's partition and, for a day without one, into the default partition") {
      val node = s"writer-role-spec-${UUID.randomUUID()}"
      val user = UUID.randomUUID()
      withWriter()((owner, _, writer, _) =>
        for
          _ <- writer.insertBatch(List(
            event(node, user, at(partitionedDay, 10)),
            event(node, user, at(partitionedDay, 11)),
            event(node, user, at(unpartitionedDay, 10)),
          ))
          day <- count(owner, partitionName(partitionedDay), node)
          default <- count(owner, s"${Table}_default", node)
        yield assertTrue(day == 2L, default == 1L),
      )
    },
    test("an event offered again is written once") {
      val node = s"writer-role-spec-${UUID.randomUUID()}"
      val e = event(node, UUID.randomUUID(), at(partitionedDay, 10))
      withWriter()((owner, _, writer, _) =>
        for
          _ <- writer.insertBatch(List(e))
          _ <- writer.insertBatch(List(e, e))
          rows <- count(owner, Table, node)
        yield assertTrue(rows == 1L),
      )
    },
    test("the role cannot update, delete, truncate, detach or drop the table or its partitions, with the maintenance mark set") {
      val node = s"writer-role-spec-${UUID.randomUUID()}"
      withWriter()((owner, _, writer, writerXa) =>
        for
          _ <-
            writer.insertBatch(List(event(node, UUID.randomUUID(), at(partitionedDay, 10)), event(node, UUID.randomUUID(), at(unpartitionedDay, 10))))
          allowed <-
            AuditWriterRole.allowed(writerXa, AuditWriterRole.refused(Table, partitionName(partitionedDay)) :+ s"DELETE FROM ${Table}_default")
          rows <- count(owner, Table, node)
        yield assertTrue(allowed == Nil, rows == 2L),
      )
    },
    test("the owner's pool still maintains the partitions, prunes the default partition under the mark, and reads for the report") {
      val node = s"writer-role-spec-${UUID.randomUUID()}"
      val user = UUID.randomUUID()
      withWriter()((owner, ownerRepository, writer, _) =>
        for
          _ <- writer.insertBatch(List(event(node, user, at(partitionedDay, 10)), event(node, user, at(unpartitionedDay, 10))))
          found <- ownerRepository.userEvents(user, at(partitionedDay, 0), at(unpartitionedDay, 23), after = None, limit = 10)
          ownership <- ownerRepository.ownership
          pruned <- ownerRepository.pruneDefault(at(unpartitionedDay, 11))
          left <- count(owner, Table, node)
          report <- ownerRepository.maintainPartitions(from = partitionedDay.plusDays(30), days = 1, keepFrom = partitionedDay.plusDays(1))
        yield assertTrue(found.size == 2, ownership.mayAlter, pruned >= 1L, left == 1L, report.created == 1, report.dropped >= 1),
      )
    },
    test("a repository without a writer pool inserts as the service's own role, as before") {
      val node = s"writer-role-spec-${UUID.randomUUID()}"
      withWriter()((owner, ownerRepository, _, _) =>
        for
          _ <- ownerRepository.insertBatch(List(event(node, UUID.randomUUID(), at(partitionedDay, 10))))
          rows <- count(owner, Table, node)
        yield assertTrue(rows == 1L),
      )
    },
    test(
      "a grant taken away after the start fails the write with the role's name for it, and the writer logs one line without what the database said",
    ) {
      val node = s"writer-role-spec-${UUID.randomUUID()}"
      withWriter()((owner, _, writer, _) =>
        for
          _ <- owner.connect(sql"REVOKE INSERT ON auth_audit_event FROM ${SqlLiteral(Role)}".update.run())
          failure <- failed(writer.insertBatch(List(event(node, UUID.randomUUID(), at(partitionedDay, 10)))))
          sizing = AuditEvents.Sizing(batchSize = 10, flushInterval = 20.millis, partitionsAhead = 0)
          layers = ZLayer.succeed[AuditEventRepository](writer) ++ ZLayer.succeed(settings) ++ NoopTracing.layer ++ SecureRandom.live
          lines <- ZIO.scoped:
            (layers >>> AuditEvents.liveWith(sizing)(AuthAudit.source, node, AuthAudit.catalog, Table)).build.flatMap: env =>
              env.get[AuditEvents].record(draft).catchAll(_ => ZIO.dieMessage("the queue refused an event")) *>
                ZTestLogger.logOutput
                  .map(_.toList.filter(_.logLevel >= LogLevel.Warning).map(_.message()).filter(_.contains("could not write")))
                  .repeatUntil(_.nonEmpty).timeoutFail(IllegalStateException("no line about the failed write"))(10.seconds)
          rows <- count(owner, Table, node)
        yield assertTrue(
          AuditWriterPool.classify(failure).isInstanceOf[AuditWriterPool.AuditWriterAccessDenied],
          lines.size == 1,
          lines.head.contains("AuditWriterAccessDenied"),
          lines.head.contains("42501"),
          !lines.head.contains(AuditWriterRole.Password),
          !lines.head.contains("permission denied"),
          rows == 0L,
        ),
      )
    } @@ TestAspect.withLiveClock,
  ).provideSomeLayer[TransactorZIO](ZTestLogger.default) @@ TestAspect.sequential @@ TestAspect.timeout(300.seconds)
