package versola.util.postgres

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException
import versola.util.audit.{AuditMetrics, AuditSource}
import versola.util.{EnvName, Secret}
import zio.*
import zio.config.magnolia.deriveConfig

import java.sql.SQLException

/** The connection the audit writer inserts through, when the deployment gave it a role of its own: a pool of a few connections as
  * a role that can only insert into the audit tables (deploy.md, "Audit log"), so that nothing running on the writer's connection can
  * change or remove what was written. `None` is a deployment that has not set `audit.writer`: the writer then uses the service's own
  * pool, as it always did. Only the batch insert goes through it; the partitions, the prune of the default partition and the report
  * stay on the service's own pool, whose role owns the tables. */
final class AuditWriterPool(val transactor: Option[TransactorZIO])

/** The `audit.writer` block of a service's config: the login of the insert-only role. The URL, the TLS settings and the timeouts are the
  * service's own `postgres` block's; only who connects differs.
  *
  * @param user            the role; it must not be the service's own (that role owns the tables and can do everything to them)
  * @param password        its password
  * @param maximumPoolSize connections the writer may hold: it writes one chunk at a time, so a few are enough
  */
final case class AuditWriterConfig(user: String, password: Secret, maximumPoolSize: Int)

object AuditWriterConfig:
  val PoolSize = 2

  /** The block as written: every key optional, so that "left out" and "half set" can be told apart, which `Config.optional` does not
    * (it reads a block missing a key as a block that is not there, and a half-set login would then quietly be the service's own role). */
  private case class Raw(user: Option[String], password: Option[Secret], maximumPoolSize: Option[Int])

  /** `audit.writer`, or `None` when the block is not there. A block that sets one of `user` and `password` without the other, an empty
    * `user` or a pool of no connections stops the start naming the key. */
  val config: Config[Option[AuditWriterConfig]] =
    Config.Nested("audit", Config.Nested("writer", deriveConfig[Raw])).optional.mapOrFail:
      case None | Some(Raw(None, None, None)) => Right(None)
      case Some(Raw(Some(user), Some(password), size)) =>
        if user.isBlank then Left(Config.Error.InvalidData(message = "audit.writer.user must be a role name"))
        else if size.exists(_ < 1) then Left(Config.Error.InvalidData(message = "audit.writer.maximum-pool-size must be at least 1"))
        else Right(Some(AuditWriterConfig(user = user, password = password, maximumPoolSize = size.getOrElse(PoolSize))))
      case Some(_) =>
        Left(Config.Error.InvalidData(message =
          "audit.writer.user and audit.writer.password must be set together, and audit.writer.maximum-pool-size needs both",
        ))

object AuditWriterPool extends BasicCodecs:

  /** The pool of the writer's role, built from the service's own `postgres` block with the login replaced, or none. It does not look at
    * the schema (the role cannot read Flyway's history table, and the service's own pool has just checked it). A login the database
    * refuses stops the start, as the service's own pool's does, naming the role and never the password; so does a role that lacks a
    * privilege the insert needs on `table` ([[check]]). */
  def live(
      serviceName: String,
      source: AuditSource,
      table: String,
  ): ZLayer[Scope & ConfigProvider & EnvName & PostgresConfig, Throwable, AuditWriterPool] =
    ZLayer.fromZIO:
      for
        provider <- ZIO.service[ConfigProvider]
        own <- ZIO.service[PostgresConfig]
        writer <- provider.load(AuditWriterConfig.config)
        transactor <- ZIO.foreach(writer): writer =>
          for
            _ <- ZIO.fail(IllegalArgumentException(
              s"audit.writer.user is '${writer.user}', the role the service connects as: the writer role must be another, one that only inserts (deploy.md, \"Audit log\")",
            )).when(writer.user == own.user)
            prod <- ZIO.serviceWith[EnvName](_.isProd)
            pool = own.copy(user = writer.user, password = writer.password, maximumPoolSize = writer.maximumPoolSize, minimumIdle = 1)
            xa <- (ZLayer.succeed(pool) >>> PostgresHikariDataSource.layer(
              serviceName = Some(s"$serviceName-audit-writer"),
              migrate = false,
              requireVerifiedTls = prod,
              checkSchema = false,
            ) >>> TransactorZIO.layer).build.map(_.get[TransactorZIO])
              // The pool throws when the database refuses its first connection (a login it does not accept); that is a failure to start like any other here.
              .catchSomeDefect { case refused: PoolInitializationException => ZIO.fail(refused) }
            _ <- check(xa, writer.user, source, table)
          yield xa
      yield AuditWriterPool(transactor)

  /** What the role holds on `table`, read once with the writer's own pool: the start is refused, naming the role and the missing grants,
    * when it cannot INSERT or read the key columns `id` and `at` (the insert's conflict rule reads them); and what it holds beyond that
    * (UPDATE, DELETE, TRUNCATE, ownership of the table or membership of the owner role) is logged as a warning and shown by the gauge
    * `audit_writer_role_restricted`, 0 then and 1 otherwise. The grants checked are those on the table itself, which is where the
    * insert is checked. */
  private case class Held(
      exists: Boolean,
      insert: Boolean,
      id: Boolean,
      at: Boolean,
      update: Boolean,
      delete: Boolean,
      truncate: Boolean,
      member: Boolean,
  ) derives DbCodec

  private def check(xa: TransactorZIO, role: String, source: AuditSource, table: String): Task[Unit] =
    val name = TableName.validated(table)
    val held = xa.connectMeasured("check-audit-writer-role"):
      sql"""
        SELECT to_regclass($name) IS NOT NULL,
               COALESCE(has_table_privilege(to_regclass($name), 'INSERT'), false),
               COALESCE(has_column_privilege(to_regclass($name), 'id', 'SELECT'), false),
               COALESCE(has_column_privilege(to_regclass($name), 'at', 'SELECT'), false),
               COALESCE(has_table_privilege(to_regclass($name), 'UPDATE'), false),
               COALESCE(has_table_privilege(to_regclass($name), 'DELETE'), false),
               COALESCE(has_table_privilege(to_regclass($name), 'TRUNCATE'), false),
               COALESCE((SELECT pg_has_role(current_user, relowner, 'MEMBER') FROM pg_class WHERE oid = to_regclass($name)), false)
      """.query[Held].run().head
    held.flatMap: row =>
      import row.*
      val missing = List("INSERT" -> insert, "SELECT (id)" -> id, "SELECT (at)" -> at).collect { case (grant, false) => grant }
      val extra = List("UPDATE" -> update, "DELETE" -> delete, "TRUNCATE" -> truncate, "ownership or membership of the owner role" -> member)
        .collect { case (grant, true) => grant }
      if !exists then
        ZIO.fail(
          IllegalStateException(s"the audit writer role '$role' cannot see the table $name: it needs USAGE on its schema (deploy.md, \"Audit log\")"),
        )
      else if missing.nonEmpty then
        ZIO.fail(IllegalStateException(
          s"the audit writer role '$role' lacks ${missing.mkString(", ")} on $name: grant them as deploy.md, \"Audit log\", says",
        ))
      else if extra.nonEmpty then
        ZIO.logWarning(s"the audit writer role '$role' holds more than it needs on $name: ${extra.mkString(", ")}") *>
          AuditMetrics.writerRoleRestricted(source, restricted = false)
      else
        ZIO.logInfo(s"the audit writer role '$role' holds only INSERT and SELECT on (id, at) of $name") *>
          AuditMetrics.writerRoleRestricted(source, restricted = true)

  /** The database refused the writer's role: its login or a privilege it needs. A class of its own, so that the line the audit writer logs
    * for a failed write (the classes and SQLSTATEs of the cause, never what the database said) says it was the writer's role. */
  final class AuditWriterAccessDenied(cause: Throwable)
    extends RuntimeException("the database refused the audit writer's role: check its login and grants (deploy.md, \"Audit log\")", cause)

  /** A refusal of the role (SQLSTATE class 28, invalid authorization; 42501, insufficient privilege) as [[AuditWriterAccessDenied]]; any
    * other failure as it is. */
  def classify(error: Throwable): Throwable =
    def states(e: Throwable): List[String] =
      if e == null then Nil
      else
        val sql = e match
          case sql: SQLException => Option(sql.getSQLState).toList ::: Option(sql.getNextException).toList.flatMap(states)
          case _ => Nil
        sql ::: states(e.getCause)
    if states(error).exists(state => state == "42501" || state.startsWith("28")) then AuditWriterAccessDenied(error) else error
