package versola.e2e.support

import zio.*

import java.lang.System as JavaSystem
import java.sql.DriverManager

/** Central's own Postgres, for the one kind of state its API refuses to produce.
  *
  * Central will not register a public client in a FAPI 2.0 tenant, nor switch a tenant to FAPI
  * 2.0 while one exists, so a client that *predates* the profile -- the case auth's runtime
  * refusal exists for -- cannot be built through the API. A spec that needs it registers the
  * client under `standard` and writes the profile here, past central's check.
  *
  * Defaults match the `local` target of `scripts/gen-env.scala`, which is also what the CI e2e
  * job stages. The write fires the `challenge_settings` notification trigger, so central's
  * cache follows it the same way it follows an API write.
  */
object CentralDatabase:

  /** Sets the stored profile of the tenant's challenge settings, bypassing central's check that
    * every client of the tenant conforms to it. Fails if the tenant has no settings row. */
  def setSecurityProfile(tenantId: String, profile: String): Task[Unit] =
    for
      url <- env("E2E_CENTRAL_DB_URL", "jdbc:postgresql://localhost:5432/auth")
      user <- env("E2E_CENTRAL_DB_USER", "dev")
      password <- env("E2E_CENTRAL_DB_PASSWORD", "1234")
      updated <- ZIO.attemptBlocking:
        val connection = DriverManager.getConnection(url, user, password).nn
        try
          val statement = connection
            .prepareStatement("UPDATE challenge_settings SET security_profile = ? WHERE tenant_id = ?").nn
          try
            statement.setString(1, profile)
            statement.setString(2, tenantId)
            statement.executeUpdate()
          finally statement.close()
        finally connection.close()
      _ <- ZIO.fail(RuntimeException(s"Expected to update the settings of tenant '$tenantId', updated $updated rows"))
        .unless(updated == 1)
    yield ()

  /** A row of central's audit log (`audit_event`), as stored. */
  case class AuditRow(
      kind: String,
      outcome: String,
      reason: Option[String],
      severity: String,
      actorType: String,
      actorId: Option[String],
      tenantId: Option[String],
      userId: Option[String],
      targetType: Option[String],
      targetId: Option[String],
      details: String,
  )

  /** The rows of the audit log that are about `targetId`, oldest first. The log has no read API for what administrators did to
    * an object that is not a user (the per-user report is the only one), and that what edge told central about the caller
    * reached the table is what a spec of the audit hook has to see, so this reads the table. Read only. */
  def auditEvents(targetId: String): Task[List[AuditRow]] =
    for
      url <- env("E2E_CENTRAL_DB_URL", "jdbc:postgresql://localhost:5432/auth")
      user <- env("E2E_CENTRAL_DB_USER", "dev")
      password <- env("E2E_CENTRAL_DB_PASSWORD", "1234")
      rows <- ZIO.attemptBlocking:
        val connection = DriverManager.getConnection(url, user, password).nn
        try
          val statement = connection
            .prepareStatement(
              "SELECT kind, outcome, reason, severity, actor_type, actor_id, tenant_id, user_id::text, target_type, target_id, details::text " +
                "FROM audit_event WHERE target_id = ? ORDER BY at, id",
            ).nn
          try
            statement.setString(1, targetId)
            val result = statement.executeQuery().nn
            val rows = List.newBuilder[AuditRow]
            while result.next() do
              rows += AuditRow(
                kind = result.getString(1).nn,
                outcome = result.getString(2).nn,
                reason = Option(result.getString(3)),
                severity = result.getString(4).nn,
                actorType = result.getString(5).nn,
                actorId = Option(result.getString(6)),
                tenantId = Option(result.getString(7)),
                userId = Option(result.getString(8)),
                targetType = Option(result.getString(9)),
                targetId = Option(result.getString(10)),
                details = result.getString(11).nn,
              )
            rows.result()
          finally statement.close()
        finally connection.close()
    yield rows

  /** The real environment: inside a test, ZIO's `System` is the test one, which is empty, and the variable an operator set to point
    * this at a Postgres of their own (the CI default is the one the services use) would be ignored. */
  private def env(name: String, default: String): Task[String] =
    ZIO.attempt(Option(JavaSystem.getenv(name)).getOrElse(default))
