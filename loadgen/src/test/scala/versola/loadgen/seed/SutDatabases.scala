package versola.loadgen.seed

import org.flywaydb.core.Flyway
import versola.loadgen.config.SutDatabaseConfig
import versola.util.postgres.{PostgresConfig, PostgresSpec}
import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}

/** Stands up the *system under test's* databases for the seeder's specs: real Postgres, auth's
  * and central's real migrations, applied by Flyway exactly as their own images apply them.
  *
  * This is not optional scaffolding. The seeder's entire risk is that it writes rows the real
  * login flow cannot use, and every defect in that class is a property of the SQL and the
  * schema -- a column order, a `bytea` escape, an enum spelled the way WebAuthn spells it rather
  * than the way `PostgresPasskeyRepository.valueOf` reads it. A fake or a hand-written schema
  * would reproduce the seeder's own assumptions and then confirm them.
  *
  * Reuses [[PostgresSpec.config]]'s host and credentials for the same reason
  * [[versola.loadgen.store.LoadgenPostgresSpec]] does -- that is what CI's service container and
  * the dev compose provide -- and only varies the database name. An unreachable Postgres fails
  * and aborts the suite, which is the required behaviour and not a nuisance: a §3.4 guard that
  * *skipped* when it could not reach a database would pass in exactly the circumstances where
  * nothing had been checked.
  */
object SutDatabases:

  /** auth's and central's schemas live in separate databases in production, and separately here:
    * both number their migrations in their own sequence, so one `flyway_schema_history` cannot
    * hold both.
    */
  val authDatabase = "loadgen_sut_auth_test"
  val centralDatabase = "loadgen_sut_central_test"

  /** sbt runs this project's tests unforked, so every spec shares one JVM and would otherwise
    * race to create the same database on first use -- the same guard `LoadgenPostgresSpec` takes.
    */
  private val creation = new Object

  private def migrationsOf(directory: String): String =
    if Files.isDirectory(Path.of(directory)) then s"filesystem:./$directory"
    else s"filesystem:../$directory"

  private def plaintext(secret: Config.Secret): String = String(secret.value.toArray)

  private def template: ZIO[Scope, Throwable, PostgresConfig] =
    PostgresSpec.config.build.map(_.get[PostgresConfig])

  /** Creates the database if absent and applies the given migrations directory to it. Idempotent
    * -- Flyway skips what it has already applied -- so several specs can share one prepared
    * database without ordering between them.
    */
  def prepare(database: String, migrationsDirectory: String): ZIO[Scope, Throwable, SutDatabaseConfig] =
    for
      base <- template
      password = new String(base.password, StandardCharsets.UTF_8)
      config = SutDatabaseConfig(
        url = base.url.substring(0, base.url.lastIndexOf('/') + 1) + database,
        user = base.user,
        password = Config.Secret(password),
      )
      // Flyway applies a schema to a database; it does not create one, and neither CI's service
      // container nor the dev compose knows about these two. Connects through `auth`, the one
      // database both of them do create.
      _ <- ZIO.attemptBlocking:
        creation.synchronized:
          val connection = DriverManager.getConnection(base.url, base.user, password)
          try
            val statement = connection.createStatement()
            try
              val existing = statement.executeQuery(s"SELECT 1 FROM pg_database WHERE datname = '$database'")
              if !existing.next() then statement.execute(s"CREATE DATABASE $database")
            finally statement.close()
          finally connection.close()
      _ <- ZIO.attemptBlocking:
        Flyway
          .configure()
          .locations(migrationsOf(migrationsDirectory))
          .dataSource(config.url, config.user, plaintext(config.password))
          .validateMigrationNaming(false)
          .load()
          .migrate()
    yield config

  def connect(config: SutDatabaseConfig): ZIO[Scope, Throwable, Connection] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(
        DriverManager.getConnection(config.url, config.user, plaintext(config.password)),
      ),
    )(connection => ZIO.attemptBlocking(connection.close()).orDie)

  /** Both SUT databases, migrated, with an open connection to each. */
  case class Sut(
      auth: Connection,
      central: Connection,
      authConfig: SutDatabaseConfig,
      centralConfig: SutDatabaseConfig,
  )

  val sut: ZIO[Scope, Throwable, Sut] =
    for
      authConfig <- prepare(authDatabase, SutSchema.SchemaOwner.Auth.migrationsDirectory)
      centralConfig <- prepare(centralDatabase, SutSchema.SchemaOwner.Central.migrationsDirectory)
      auth <- connect(authConfig)
      central <- connect(centralConfig)
    yield Sut(auth, central, authConfig, centralConfig)

  def statement(connection: Connection, sql: String): Task[Unit] =
    ZIO.attemptBlocking:
      val prepared = connection.prepareStatement(sql)
      try prepared.execute()
      finally prepared.close()

  /** Applies a schema change, runs the body, and reverts -- so a spec can ask what the guard does
    * about a change to auth's schema without needing a second migrated database per case.
    *
    * Takes lists rather than single statements because adding a `NOT NULL` column to a table that
    * already holds rows takes two: a `DEFAULT` to backfill with, then a `DROP DEFAULT`. That is
    * also how a real migration would do it, so the resulting schema is the one the guard would
    * actually meet -- and the specs here run after `SeederSmokeSpec` has populated the same
    * tables, so the single-statement form only ever worked in isolation.
    */
  def withSchemaChange[R, A](connection: Connection, apply: List[String], revert: List[String])(
      body: ZIO[R, Throwable, A],
  ): ZIO[R, Throwable, A] =
    ZIO.acquireReleaseWith(ZIO.foreachDiscard(apply)(statement(connection, _)))(_ =>
      ZIO.foreachDiscard(revert)(statement(connection, _)).orDie,
    )(_ => body)
