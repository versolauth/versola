package versola.e2e.support

import zio.*

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

  private def env(name: String, default: String): Task[String] =
    System.env(name).map(_.getOrElse(default))
