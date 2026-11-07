package versola.util.postgres

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.EnvName
import versola.util.audit.*
import zio.*
import zio.json.ast.Json
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

import java.time.Instant
import java.util.UUID

/** The audit writer connected as a role that can only insert, against a real Postgres: what the role can write (the parent table, and
  * through it the day partitions, the default partition and partitions made later by the owner), what it is refused (everything else,
  * with the maintenance mark set or not), and how the `audit.writer` block of the config becomes the pool. The table is the audit log's
  * DDL in a schema of its own, with the append-only triggers of the real migrations. */
object PostgresAuditWriterRoleSpec extends PostgresSpec:

  private val Schema = "audit_writer_role_spec"
  private val Table = "writer_role_audit_event"
  private val Role = "versola_audit_writer_util_spec"
  private val Source = AuditSource("writer-role-spec")
  private val ExtraSource = AuditSource("writer-role-spec-extra")
  private val table = AuditTestTable(Schema, Table)
  private val qualified = s"$Schema.$Table"

  private def event(at: Instant, id: UUID = UUID.randomUUID()) =
    AuditEvent(
      id = id,
      at = at,
      tenantId = None,
      source = AuditSource("svc"),
      nodeId = "node-1",
      kind = AuditKind("thing.done"),
      outcome = AuditOutcome.success,
      reason = None,
      severity = AuditSeverity.info,
      actorType = ActorType("admin"),
      actorId = None,
      userId = None,
      clientId = None,
      sessionId = None,
      ip = None,
      userAgent = None,
      traceId = None,
      targetType = None,
      targetId = None,
      details = Json.Obj(),
    )

  private val partitioned = Instant.parse("2026-11-04T10:00:00Z")
  private val unpartitioned = Instant.parse("2026-11-05T10:00:00Z")
  private val laterDay = Instant.parse("2026-11-06T10:00:00Z")

  /** The table, its default partition and the partition of 2026-11-04, with the triggers of the migration. */
  private val reset: URIO[TransactorZIO, Unit] =
    table.reset *> ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect:
        val relation = SqlLiteral(qualified)
        sql"CREATE TABLE ${SqlLiteral(s"${qualified}_20261104")} PARTITION OF $relation FOR VALUES FROM ('2026-11-04 00:00:00+00') TO ('2026-11-05 00:00:00+00')".update.run()
        sql"""
          CREATE FUNCTION ${SqlLiteral(Schema)}.immutable() RETURNS trigger AS $$$$
          BEGIN
            IF current_setting('versola.audit_maintenance', true) IS DISTINCT FROM 'on' THEN
              RAISE EXCEPTION 'append-only' USING ERRCODE = 'insufficient_privilege';
            END IF;
            RETURN NULL;
          END $$$$ LANGUAGE plpgsql
        """.update.run()
        sql"CREATE TRIGGER immutable AFTER UPDATE OR DELETE ON $relation FOR EACH ROW EXECUTE FUNCTION ${SqlLiteral(Schema)}.immutable()".update.run()
        sql"CREATE TRIGGER no_truncate BEFORE TRUNCATE ON $relation FOR EACH STATEMENT EXECUTE FUNCTION ${SqlLiteral(Schema)}.immutable()".update.run()
        ()
      .orDie

  private def withWriter[A](keyColumns: Boolean = true)(use: (TransactorZIO, TransactorZIO) => Task[A]): ZIO[TransactorZIO, Throwable, A] =
    ZIO.serviceWithZIO[TransactorZIO]: owner =>
      ZIO.scoped:
        for
          _ <- AuditWriterRole.scoped(owner, Role, Schema, List(qualified), keyColumns)
          pool <- AuditWriterRole.pool(Role, AuditWriterRole.Password, Source, Table).provideSomeLayer[Scope](PostgresSpec.processConfig)
          writer <- ZIO.fromOption(pool.transactor).orElseFail(IllegalStateException("no pool"))
          result <- use(owner, writer)
        yield result

  private def insert(writer: TransactorZIO, events: List[AuditEvent]) =
    table.inTransaction(writer)(AuditInsert.insertBatch(events, table = Table))

  private def failed[A](task: Task[A]): Task[Throwable] = task.flip.mapError(_ => IllegalStateException("expected a failure"))

  private def block(entries: (String, String)*) =
    ConfigProvider.fromMap(entries.toMap).kebabCase

  /** The pool the app would build from `provider` on the spec's `postgres` block. */
  private def built(provider: ConfigProvider, source: AuditSource = Source) =
    ZIO.scoped:
      AuditWriterPool.live(
        serviceName = "spec",
        source = source,
        table = Table,
      ).build.map(_.get[AuditWriterPool].transactor.isDefined).provideSomeLayer[Scope](
        ZLayer.succeed(provider) ++ ZLayer.succeed[EnvName](EnvName.Test("spec")) ++ PostgresSpec.processConfig,
      )

  private def restricted(source: AuditSource): UIO[Double] =
    Metric.gauge("audit_writer_role_restricted").tagged(MetricLabel("source", source.value)).value.map(_.value)

  private def lines: UIO[List[String]] =
    ZTestLogger.logOutput.map(_.toList.map(_.message()).filter(_.contains("audit writer role")))

  private val credentials = block("audit.writer.user" -> Role, "audit.writer.password" -> AuditWriterRole.Password)

  def spec = suite("the audit writer as an insert-only role")(
    test("writes into a day's partition, into the default partition for a day without one, and into a partition the owner makes later") {
      withWriter()((owner, writer) =>
        for
          _ <- insert(writer, List(event(partitioned), event(partitioned), event(unpartitioned)))
          _ <- owner.connect(
            sql"CREATE TABLE ${SqlLiteral(s"${qualified}_20261106")} PARTITION OF ${SqlLiteral(qualified)} FOR VALUES FROM ('2026-11-06 00:00:00+00') TO ('2026-11-07 00:00:00+00')".update.run(),
          )
          _ <- insert(writer, List(event(laterDay)))
          day <- table.count(owner, s"${Table}_20261104")
          default <- table.count(owner, s"${Table}_default")
          later <- table.count(owner, s"${Table}_20261106")
        yield assertTrue(day == 2L, default == 1L, later == 1L),
      )
    },
    test("writes an event once however often it is offered, which takes SELECT on the key columns and nothing else") {
      val e = event(partitioned)
      withWriter()((owner, writer) =>
        for
          _ <- insert(writer, List(e))
          _ <- insert(writer, List(e, e))
          rows <- table.count(owner, Table)
        yield assertTrue(rows == 1L),
      )
    },
    test("without the grant on the key columns the insert is refused, and the refusal is named as the writer role's") {
      withWriter()((owner, writer) =>
        for
          _ <- owner.connect(sql"REVOKE SELECT (id, at) ON ${SqlLiteral(qualified)} FROM ${SqlLiteral(Role)}".update.run())
          failure <- failed(insert(writer, List(event(partitioned))))
          rows <- table.count(owner, Table)
        yield assertTrue(failure.isInstanceOf[Throwable], rows == 0L) &&
          assertTrue(AuditWriterPool.classify(failure).isInstanceOf[AuditWriterPool.AuditWriterAccessDenied]),
      )
    },
    test("cannot update, delete, truncate, detach, disable a trigger or drop, with the maintenance mark set in its transaction") {
      withWriter()((owner, writer) =>
        for
          _ <- insert(writer, List(event(partitioned), event(unpartitioned)))
          allowed <- AuditWriterRole.allowed(writer, AuditWriterRole.refused(qualified, s"${qualified}_20261104"))
          rows <- table.count(owner, Table)
          partitions <- owner.connect(
            sql"SELECT COUNT(*) FROM pg_inherits WHERE inhparent = ${SqlLiteral(s"'$qualified'::regclass")}".query[Long].run().head,
          )
        yield assertTrue(allowed == Nil, rows == 2L, partitions == 2L),
      )
    },
    test("cannot read what it wrote beyond the key columns, nor make a table") {
      withWriter()((_, writer) =>
        for
          allowed <- AuditWriterRole.allowed(
            writer,
            List(s"SELECT kind FROM $qualified", s"SELECT * FROM $qualified", s"CREATE TABLE $Schema.writer_made (a int)"),
          )
        yield assertTrue(allowed == Nil),
      )
    },
    test("the owner still creates, attaches and drops partitions and deletes under the maintenance mark while the writer has its role") {
      withWriter()((owner, writer) =>
        for
          _ <- insert(writer, List(event(unpartitioned)))
          deleted <- owner.transact:
            sql"SET LOCAL versola.audit_maintenance = 'on'".update.run()
            sql"DELETE FROM ${SqlLiteral(s"${qualified}_default")}".update.run()
          _ <- owner.connect(sql"DROP TABLE ${SqlLiteral(s"${qualified}_20261104")}".update.run())
          rows <- table.count(owner, Table)
        yield assertTrue(deleted == 1, rows == 0L),
      )
    },
    suite("the audit.writer block")(
      test("a config without it builds no pool: the writer uses the service's own") {
        built(block("audit.retention" -> "7 days")).map(pool => assertTrue(!pool))
      },
      test("a config without an audit block builds none either") {
        built(block()).map(pool => assertTrue(!pool))
      },
      test("the keys are read as they are written") {
        for config <- credentials.load(AuditWriterConfig.config)
        yield assertTrue(
          config.map(_.user) == Some(Role),
          config.map(c => new String(c.password, java.nio.charset.StandardCharsets.UTF_8)) == Some(AuditWriterRole.Password),
          config.map(_.maximumPoolSize) == Some(AuditWriterConfig.PoolSize),
        )
      },
      test("a user and a password build the pool") {
        ZIO.scoped(
          for
            owner <- ZIO.service[TransactorZIO]
            _ <- AuditWriterRole.scoped(owner, Role, Schema, List(qualified), keyColumns = true)
            pool <- built(credentials)
            gauge <- restricted(Source)
            said <- lines
          yield assertTrue(pool, gauge == 1.0, said.exists(_.contains(s"'$Role' holds only INSERT and SELECT on (id, at) of $Table"))),
        )
      },
      test("a role without the grant on the key columns stops the start, naming the role and the grant, and the pool is not used") {
        ZIO.scoped(
          for
            owner <- ZIO.service[TransactorZIO]
            _ <- AuditWriterRole.scoped(owner, Role, Schema, List(qualified), keyColumns = false)
            failure <- failed(built(credentials))
          yield assertTrue(
            failure.getMessage.contains(s"'$Role' lacks SELECT (id), SELECT (at) on $Table"),
            !failure.getMessage.contains(AuditWriterRole.Password),
          ),
        )
      },
      test("a role without INSERT stops the start naming it") {
        ZIO.scoped(
          for
            owner <- ZIO.service[TransactorZIO]
            _ <- AuditWriterRole.scoped(owner, Role, Schema, List(qualified), keyColumns = true)
            _ <- owner.connect(sql"REVOKE INSERT ON ${SqlLiteral(qualified)} FROM ${SqlLiteral(Role)}".update.run())
            failure <- failed(built(credentials))
          yield assertTrue(failure.getMessage.contains(s"'$Role' lacks INSERT on $Table")),
        )
      },
      test("a role that cannot see the table's schema stops the start, naming the schema grant") {
        ZIO.scoped(
          for
            owner <- ZIO.service[TransactorZIO]
            _ <- AuditWriterRole.scoped(owner, Role, Schema, List(qualified), keyColumns = true)
            _ <- owner.connect(sql"REVOKE USAGE ON SCHEMA ${SqlLiteral(Schema)} FROM ${SqlLiteral(Role)}".update.run())
            failure <- failed(built(credentials))
          yield assertTrue(failure.getMessage.contains("USAGE on its schema")),
        )
      },
      test("a role that can also update, delete or truncate starts, with a warning naming what it holds and the gauge at 0") {
        ZIO.scoped(
          for
            owner <- ZIO.service[TransactorZIO]
            _ <- AuditWriterRole.scoped(owner, Role, Schema, List(qualified), keyColumns = true)
            _ <- owner.connect(sql"GRANT UPDATE, DELETE, TRUNCATE ON ${SqlLiteral(qualified)} TO ${SqlLiteral(Role)}".update.run())
            pool <- built(credentials, ExtraSource)
            gauge <- restricted(ExtraSource)
            said <- lines
          yield assertTrue(pool, gauge == 0.0, said.exists(_.contains(s"'$Role' holds more than it needs on $Table: UPDATE, DELETE, TRUNCATE"))),
        )
      },
      test("a user without a password, a password without a user, and a pool size without either stop the start naming the keys") {
        for
          user <- failed(built(block("audit.writer.user" -> Role)))
          password <- failed(built(block("audit.writer.password" -> "x")))
          size <- failed(built(block("audit.writer.maximum-pool-size" -> "3")))
        yield assertTrue(
          user.toString.contains("audit.writer.user and audit.writer.password"),
          password.toString.contains("audit.writer.user and audit.writer.password"),
          size.toString.contains("audit.writer.user and audit.writer.password"),
        )
      },
      test("an empty user and a pool of no connections stop the start") {
        for
          blank <- failed(built(block("audit.writer.user" -> " ", "audit.writer.password" -> "x")))
          none <- failed(built(block("audit.writer.user" -> Role, "audit.writer.password" -> "x", "audit.writer.maximum-pool-size" -> "0")))
        yield assertTrue(blank.toString.contains("audit.writer.user"), none.toString.contains("audit.writer.maximum-pool-size"))
      },
      test("the service's own role is refused as the writer's, since it would restrict nothing") {
        for
          own <- ZIO.service[PostgresConfig].map(_.user).provide(PostgresSpec.processConfig)
          refusal <- failed(built(block("audit.writer.user" -> own, "audit.writer.password" -> "x")))
        yield assertTrue(refusal.getMessage.contains("another"))
      },
      test("a password the database refuses stops the start naming the role and never the password") {
        val wrong = "not-the-password-4f3a9c"
        ZIO.scoped(
          for
            owner <- ZIO.service[TransactorZIO]
            _ <- AuditWriterRole.scoped(owner, Role, Schema, List(qualified), keyColumns = true)
            failure <- failed(built(block("audit.writer.user" -> Role, "audit.writer.password" -> wrong)))
            text = failure.toString + Option(failure.getCause).fold("")(_.toString)
          yield assertTrue(text.contains(Role), !text.contains(wrong)),
        )
      },
    ),
  ).provideSomeLayer[TransactorZIO](ZTestLogger.default) @@ TestAspect.before(reset) @@ TestAspect.sequential @@ TestAspect.withLiveClock
