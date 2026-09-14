package versola.loadgen.store

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource, PostgresSpec}
import zio.*
import zio.test.ZIOSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.DriverManager

/** What `util-postgres`' [[PostgresSpec]] is for a service, for the emulator's own store: a real
  * Postgres, the real migrations, the real magnum codecs.
  *
  * There is no in-memory implementation of these repositories to test against instead, and a
  * fake would not have caught either of the two defects this suite was written after -- a
  * `listLive` predicate that silently matched no web-cookie session, and a persisted shape
  * missing a credential -- because both were properties of the SQL and the schema, not of the
  * Scala around them.
  */
abstract class LoadgenPostgresSpec extends ZIOSpec[TransactorZIO]:

  override val bootstrap = LoadgenPostgresSpec.transactor

object LoadgenPostgresSpec:

  /** Its own database, not the `auth` one [[PostgresSpec]] uses, because that is the arrangement
    * production has: this schema numbers its migrations from `V0001` exactly as auth's does, so
    * sharing a database would put two unrelated `V0001`s in one `flyway_schema_history`. See
    * [[LoadgenMigrations]].
    */
  private val databaseName = "loadgen_test"

  /** sbt runs this project's tests unforked, so every spec shares one JVM and every one of them
    * would otherwise race to create the database on first use.
    */
  private val creation = new Object

  /** Production's own constant, not a path of this spec's, when sbt runs from the repository
    * root -- which it does; the fallback covers the module directory being the root, as
    * [[MigrationNamingSpec]]'s does. A spec that named the directory itself would keep passing
    * if [[LoadgenMigrations.locations]] were wrong, which is the failure that motivated
    * `MigrationNamingSpec` in the first place.
    */
  private val migrationLocations: Seq[String] =
    if Files.isDirectory(Path.of("loadgen", "migrations")) then LoadgenMigrations.locations
    else Seq("filesystem:./migrations")

  /** [[PostgresSpec.config]] names the host and credentials CI's service container and the dev
    * compose both provide; only the database differs, so it is reused rather than restated.
    */
  private val config: ZLayer[Any, Throwable, PostgresConfig] =
    PostgresSpec.config >>> ZLayer.fromZIO:
      ZIO.serviceWithZIO[PostgresConfig]: auth =>
        createDatabase(auth).as:
          auth.copy(url = auth.url.substring(0, auth.url.lastIndexOf('/') + 1) + databaseName)

  /** Flyway applies a schema to a database; it does not create one, and neither CI's service
    * container nor the dev compose knows about this database. Connects through `auth` -- the one
    * database both of them do create -- rather than `postgres`, so this needs no privilege
    * [[PostgresSpec]] does not already rely on.
    */
  private def createDatabase(auth: PostgresConfig): Task[Unit] =
    ZIO.attemptBlocking:
      creation.synchronized:
        val password = new String(auth.password, StandardCharsets.UTF_8)
        val connection = DriverManager.getConnection(auth.url, auth.user, password)
        try
          val statement = connection.createStatement()
          try
            val existing = statement.executeQuery(s"SELECT 1 FROM pg_database WHERE datname = '$databaseName'")
            if !existing.next() then statement.execute(s"CREATE DATABASE $databaseName")
          finally statement.close()
        finally connection.close()

  val transactor: ZLayer[Any, Throwable, TransactorZIO] =
    config >>> (Scope.default >>> PostgresHikariDataSource.layer(
      serviceName = Some("loadgen-store-test"),
      migrate = true,
      validateOnMigrate = false,
      migrationLocations = Some(migrationLocations),
    )) >>> TransactorZIO.layer
