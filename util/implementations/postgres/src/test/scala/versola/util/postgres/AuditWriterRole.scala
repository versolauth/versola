package versola.util.postgres

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.EnvName
import versola.util.audit.AuditSource
import zio.*

/** The insert-only role of the audit writer, made the way deploy.md tells the operator to make it, for the specs that run against a real
  * Postgres: the role, its grants on the audit tables, a pool that connects as it, and the statements it must be refused. A role is
  * cluster-wide, so each spec names its own. */
object AuditWriterRole:

  val Password = "writer-spec-password"

  /** The role with INSERT on `tables` (each `schema.table`) and, unless `keyColumns` is false, SELECT on their `(id, at)`; dropped with the
    * scope, together with what it was granted. */
  def scoped(owner: TransactorZIO, role: String, schema: String, tables: List[String], keyColumns: Boolean): ZIO[Scope, Throwable, Unit] =
    val name = SqlLiteral(role)
    val drop = owner.connect:
      sql"""
        DO $$$$ BEGIN
          IF EXISTS (SELECT FROM pg_roles WHERE rolname = ${SqlLiteral(s"'$role'")}) THEN DROP OWNED BY $name; DROP ROLE $name; END IF;
        END $$$$
      """.update.run()
      ()
    ZIO.acquireRelease(
      drop *> owner.connect:
        sql"CREATE ROLE $name LOGIN PASSWORD ${SqlLiteral(s"'$Password'")}".update.run()
        sql"GRANT USAGE ON SCHEMA ${SqlLiteral(schema)} TO $name".update.run()
        // The pool resolves the table by its bare name, as the service's own does.
        sql"ALTER ROLE $name SET search_path = ${SqlLiteral(schema)}, public".update.run()
        tables.foreach: table =>
          sql"GRANT INSERT ON ${SqlLiteral(table)} TO $name".update.run()
          if keyColumns then sql"GRANT SELECT (id, at) ON ${SqlLiteral(table)} TO $name".update.run()
        (),
    )(_ => drop.orDie).unit

  /** The writer's pool, as the app builds it from the `audit.writer` block of its config, on top of the spec's own `postgres` block. */
  def pool(role: String, password: String, source: AuditSource, table: String): ZIO[Scope & PostgresConfig, Throwable, AuditWriterPool] =
    val provider = ConfigProvider.fromMap(Map("audit.writer.user" -> role, "audit.writer.password" -> password)).kebabCase
    AuditWriterPool.live(
      serviceName = "spec",
      source = source,
      table = table,
    ).build.map(_.get[AuditWriterPool]).provideSomeLayer[Scope & PostgresConfig](
      ZLayer.succeed(provider) ++ ZLayer.succeed[EnvName](EnvName.Test("spec")),
    )

  /** What each statement does as the writer, with the maintenance mark set in its transaction (the mark must not be what lets one through):
    * the statements that succeeded. */
  def allowed(writer: TransactorZIO, statements: List[String]): Task[List[String]] =
    ZIO.foreach(statements): statement =>
      writer.transact:
        sql"SET LOCAL versola.audit_maintenance = 'on'".update.run()
        sql"${SqlLiteral(statement)}".update.run()
      .exit.map(statement -> _)
    .map(_.collect { case (statement, Exit.Success(_)) => statement })

  /** The statements a writer must be refused on `table`, and on `partition`, a partition of it. */
  def refused(table: String, partition: String): List[String] =
    List(
      s"UPDATE $table SET kind = 'changed'",
      s"DELETE FROM $table",
      s"DELETE FROM $partition",
      s"TRUNCATE $table",
      s"TRUNCATE $partition",
      s"DROP TABLE $partition",
      s"ALTER TABLE $table DETACH PARTITION $partition",
      s"ALTER TABLE $table DISABLE TRIGGER ALL",
      s"DROP TABLE $table",
    )
