package versola.util.postgres

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import zio.*

import java.time.Instant
import java.util.UUID

/** A copy of the audit log's table in a schema of its own (the service's migration makes the real one, and a spec must not make a
  * second in the schema the migrations run in), with the default partition every event of a day without a partition goes to, and a
  * trigger that refuses a kind, to stand for what the real table may refuse that this copy does not. A spec names its own schema, so
  * that specs running side by side do not reset each other's table. */
final class AuditTestTable(schema: String):

  /** The statements of the copy run inside a transaction that sees it, as the service's connection sees its own table. */
  def inTransaction[A](xa: TransactorZIO)(f: DbTx ?=> A): Task[A] =
    xa.transact:
      sql"SET LOCAL search_path = ${SqlLiteral(schema)}, public".update.run()
      f

  def rows(xa: TransactorZIO): Task[List[AuditTestTable.Row]] =
    inTransaction(xa):
      sql"""
        SELECT id, at, tenant_id, source, node_id, kind, outcome, reason, severity, actor_type, actor_id, user_id, client_id,
               session_id, host(ip), user_agent, trace_id, target_type, target_id, details::text
        FROM audit_event ORDER BY kind, id
      """.query[AuditTestTable.Row].run().toList

  def count(xa: TransactorZIO, table: String): Task[Long] =
    inTransaction(xa)(sql"SELECT COUNT(*) FROM ${SqlLiteral(table)}".query[Long].run().head)

  /** Every INSERT statement into the copy sleeps `millis` first (a statement trigger, as a slow disk or a slow trigger would): the
    * driver sends a batch as one statement per row inside one transaction, so a batch of `n` rows takes `n * millis`. */
  def slowInserts(xa: TransactorZIO, millis: Int): Task[Unit] =
    xa.connect:
      val name = SqlLiteral(schema)
      sql"""
        CREATE OR REPLACE FUNCTION $name.slow_insert() RETURNS trigger AS $$$$
        BEGIN PERFORM pg_sleep(${SqlLiteral(s"$millis / 1000.0")}); RETURN NULL; END $$$$ LANGUAGE plpgsql
      """.update.run()
      sql"""
        CREATE TRIGGER slow_insert AFTER INSERT ON $name.audit_event
        FOR EACH STATEMENT EXECUTE FUNCTION $name.slow_insert()
      """.update.run()
      ()
    .unit

  /** Ends the sessions that were marked with `application_name`, so that a statement a spec abandoned does not hold the copy's locks
    * for the next spec. */
  def endSessions(xa: TransactorZIO, applicationName: String): Task[Unit] =
    xa.connect:
      sql"""
        SELECT pg_terminate_backend(pid) FROM pg_stat_activity
        WHERE application_name = $applicationName AND pid <> pg_backend_pid()
      """.query[Boolean].run()
      ()

  val reset: URIO[TransactorZIO, Unit] =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect:
        val name = SqlLiteral(schema)
        sql"DROP SCHEMA IF EXISTS $name CASCADE".update.run()
        sql"CREATE SCHEMA $name".update.run()
        sql"""
          CREATE TABLE $name.audit_event (
            id UUID NOT NULL, at TIMESTAMPTZ NOT NULL, tenant_id TEXT, source TEXT NOT NULL, node_id TEXT NOT NULL,
            kind TEXT NOT NULL, outcome TEXT NOT NULL, reason TEXT, severity TEXT NOT NULL,
            actor_type TEXT NOT NULL, actor_id TEXT, user_id UUID, client_id TEXT, session_id TEXT,
            ip INET, user_agent TEXT, trace_id TEXT, target_type TEXT, target_id TEXT,
            details JSONB NOT NULL,
            PRIMARY KEY (id, at)
          ) PARTITION BY RANGE (at)
        """.update.run()
        sql"CREATE TABLE $name.audit_event_default PARTITION OF $name.audit_event DEFAULT".update.run()
        sql"CREATE TABLE $name.change_probe (id INT PRIMARY KEY)".update.run()
        sql"""
          CREATE FUNCTION $name.refuse_poison() RETURNS trigger AS $$$$
          BEGIN
            IF NEW.kind = 'thing.poison' THEN RAISE EXCEPTION 'refused'; END IF;
            RETURN NEW;
          END $$$$ LANGUAGE plpgsql
        """.update.run()
        sql"""
          CREATE TRIGGER refuse_poison BEFORE INSERT ON $name.audit_event
          FOR EACH ROW EXECUTE FUNCTION $name.refuse_poison()
        """.update.run()
        ()
      .orDie

object AuditTestTable:
  /** An event as the copy stores it, `ip` as the address text and `details` as JSON text. */
  case class Row(
      id: UUID,
      at: Instant,
      tenantId: Option[String],
      source: String,
      nodeId: String,
      kind: String,
      outcome: String,
      reason: Option[String],
      severity: String,
      actorType: String,
      actorId: Option[String],
      userId: Option[UUID],
      clientId: Option[String],
      sessionId: Option[String],
      ip: Option[String],
      userAgent: Option[String],
      traceId: Option[String],
      targetType: Option[String],
      targetId: Option[String],
      details: String,
  ) derives DbCodec
