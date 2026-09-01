package versola.migrate

import com.typesafe.config.ConfigFactory
import org.flywaydb.core.Flyway

import java.io.File

/** Standalone migration runner, shipped inside versola-tools' image (see
  * docker/versola-tools/entrypoint.sh's "migrate" dispatch branch, added alongside this).
  *
  * Backs `versola migrate` (see versola-cli's internal/deploy/migrate.go): applies each of
  * auth/central/edge's own Flyway migrations against its own schema, independently, from mounted
  * copies of the exact same auth.conf/central.conf/edge.conf files `versola configure` already
  * generated for the real services (see compose.fragment.yml.template / .vps.yml.template's
  * `migrate` service, which mounts them read-only the same way auth/central/edge's own services
  * do). There is deliberately no separate config-construction path here -- reading the very same
  * files the real services will start from means there is no way for what this applies to drift
  * from what they expect to find already there.
  *
  * A plain synchronous `main`, not a ZIO app, and Flyway is handed a raw JDBC URL/user/password
  * rather than going through `PostgresHikariDataSource`/HikariCP (see build.sbt's `migrateTool`
  * project for the full reasoning) -- this runs once, sequentially, applying at most a handful of
  * migrations per service before exiting; there is no concurrent workload here for a connection
  * pool to serve, and pulling in `util`/`util-postgres` for one just to reuse their Flyway
  * plumbing is what dragged ZIO/HikariCP/CEL onto this image's classpath and broke jlink for it.
  * The Flyway configuration below is therefore its own copy, not a call into
  * `PostgresHikariDataSource.layer` -- see the comment on `migrate` below for how it deliberately
  * differs from that copy, not just duplicates it.
  */
object MigrateTool:

  private case class Target(serviceName: String, configPath: String, migrationsLocation: String)

  // Matches wherever compose.fragment.yml.template / .vps.yml.template mount the generated
  // auth.conf/central.conf/edge.conf inside this image -- see docker/Dockerfile.tools' WORKDIR
  // (/opt/versola-tools). Overridable via CONFIG_DIR for anyone invoking this image directly
  // instead of through those compose fragments.
  private val configDir = Option(System.getenv("CONFIG_DIR")).getOrElse("/opt/versola-tools/config")

  private val targets = List(
    Target("auth", s"$configDir/auth.conf", "filesystem:./auth/implementations/postgres/migrations"),
    Target("central", s"$configDir/central.conf", "filesystem:./central/implementations/postgres/migrations"),
    Target("edge", s"$configDir/edge.conf", "filesystem:./edge/implementations/postgres/migrations"),
  )

  /** Just the three fields Flyway itself needs -- unlike `PostgresConfig` (which HikariCP's pool
    * tuning also needs: max-pool-size, connection-timeout, etc.), there is no pool here to tune.
    * `password` stays a plain `String`, not wrapped in `Secret` -- that newtype exists to keep a
    * long-lived config value out of a service's own logs/toString over its whole process
    * lifetime; this process reads it once, hands it straight to Flyway, and exits.
    */
  private case class PgConnection(url: String, user: String, password: String)

  /** Reads just the `postgres.url`/`postgres.user`/`postgres.password` fields straight off the
    * mounted .conf file via plain typesafe-config -- no `ConfigProvider`/kebab-case conversion
    * needed the way `PostgresHikariDataSource.transactor` needs for `PostgresConfig`'s derived
    * decoder, since these three keys are looked up by literal HOCON path instead of derived from
    * a case class' camelCase field names.
    *
    * Deliberately checks the file exists before parsing it -- typesafe-config silently returns
    * an *empty* config for a missing file rather than failing, which would otherwise surface here
    * as an opaque "No configuration setting found for key 'postgres'" instead of naming the
    * actual missing mount.
    */
  private def readConnection(path: String): PgConnection =
    val file = File(path)
    if !file.isFile then throw java.io.FileNotFoundException(s"Config file not found: $path")
    val conf = ConfigFactory.parseFile(file).resolve()
    PgConnection(
      url = conf.getString("postgres.url"),
      user = conf.getString("postgres.user"),
      password = conf.getString("postgres.password"),
    )

  private def migrate(target: Target): Unit =
    println(s"${target.serviceName}: applying migrations from ${target.configPath}")
    val connection = readConnection(target.configPath)

    val flyway = Flyway
      .configure()
      .locations(target.migrationsLocation)
      .dataSource(connection.url, connection.user, connection.password)
      // Never disabled, unlike `PostgresHikariDataSource.layer`'s own `validateOnMigrate` (which
      // services can turn off in tests/development) -- this is the one place that actually
      // mutates a real schema, so there's no context where skipping Flyway's own pre-migrate
      // validation is the right call.
      .validateOnMigrate(true)
      .cleanDisabled(true)
      .validateMigrationNaming(false)
      // No `.ignoreMigrationPatterns(...)` override here, unlike the services' own `validate()`
      // path -- that path deliberately tolerates a database ahead of the build it's running (see
      // its own comment: a rollback deploy must still start against a schema a newer version
      // already migrated). This tool has no such excuse: it's the one place that actually applies
      // migrations, always meant to run against the SAME version's own migrations, never to roll
      // one back. Leaving Flyway's defaults in place (only `*:future` is ignored out of the box;
      // `*:missing` is not) means an unresolvable applied migration -- e.g. this image's
      // migrations mount pointing at the wrong thing entirely -- fails loudly here instead of
      // being silently waved through, which is the stricter behavior this tool should have and
      // the per-service startup check specifically should not.
      .outOfOrder(true)
      .load()

    flyway.migrate()
    println(s"${target.serviceName}: migrations complete")

  def main(args: Array[String]): Unit =
    try targets.foreach(migrate)
    catch
      case t: Throwable =>
        System.err.println(s"versola-tools migrate: failed -- ${t.getMessage}")
        t.printStackTrace()
        sys.exit(1)
