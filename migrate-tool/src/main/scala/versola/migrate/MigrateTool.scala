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
  * Two optional args, forwarded here by entrypoint.sh's own "migrate" dispatch branch (which
  * shifts argv[0] off before exec'ing this): `--dry-run` reports each target's pending migrations
  * via Flyway's own `info()` without applying anything, and `--service <name>` restricts either
  * mode to one of "auth"/"central"/"edge" instead of all three. Both back versola-cli's own
  * `--dry-run`/`--service` flags on `versola migrate` -- see its own comment for why a CI
  * pipeline needs both (checking what WOULD apply without a human in the loop, and being able to
  * fail on just one service's migrations rather than all three at once).
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

  /** Builds the one Flyway instance both `migrate` and `checkPending` need --
    * identical configuration either way, since a dry run has to validate
    * against the exact same migration history and connection a real one
    * would, or "what would apply" stops meaning anything.
    */
  private def buildFlyway(target: Target): Flyway =
    val connection = readConnection(target.configPath)
    Flyway
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

  private def migrate(target: Target): Unit =
    println(s"${target.serviceName}: applying migrations from ${target.configPath}")
    val flyway = buildFlyway(target)
    flyway.migrate()
    println(s"${target.serviceName}: migrations complete")

  /** `--dry-run`'s own path: validates the same way `migrate` would, then
    * lists what Flyway's own history table says is still pending, via
    * `info().pending()` -- standard OSS Flyway, not a Teams-only feature
    * (unlike `dryRunOutput`, which generates SQL and IS Teams-only) --
    * without ever calling `.migrate()`, so nothing here touches the schema.
    */
  private def checkPending(target: Target): Unit =
    println(s"${target.serviceName}: checking pending migrations against ${target.configPath}")
    val flyway = buildFlyway(target)
    flyway.validate()
    val pending = flyway.info().pending()
    if pending.isEmpty then println(s"${target.serviceName}: up to date, nothing to apply")
    else
      println(s"${target.serviceName}: ${pending.length} pending migration(s):")
      pending.foreach(m => println(s"  ${m.getVersion} - ${m.getDescription}"))

  /** `--service <name>` restricts `targets` to just that one -- validated
    * against the fixed list above rather than trusting the string, so a
    * typo fails with a clear message instead of quietly matching nothing
    * and reporting "0 targets" as if that were a success.
    */
  private def selectTargets(serviceArg: Option[String]): List[Target] =
    serviceArg match
      case None => targets
      case Some(name) =>
        targets.find(_.serviceName == name) match
          case Some(t) => List(t)
          case None =>
            System.err.println(
              s"versola-tools migrate: unknown --service '$name' (expected one of ${targets.map(_.serviceName).mkString(", ")})"
            )
            sys.exit(1)

  def main(args: Array[String]): Unit =
    val dryRun = args.contains("--dry-run")
    val serviceIdx = args.indexOf("--service")
    val serviceArg =
      if serviceIdx < 0 then None
      else if serviceIdx + 1 < args.length then Some(args(serviceIdx + 1))
      else
        // "--service" present with nothing after it (e.g. it's the last
        // token) is a malformed command, not "no --service given" -- that
        // distinction matters because None means "every target" (see
        // selectTargets below). Falling through to None here would turn a
        // typo'd/truncated production command into migrations against
        // every schema instead of failing loudly, the same failure mode
        // selectTargets' own unknown-name branch exists to avoid for a bad
        // value.
        System.err.println("versola-tools migrate: --service requires a value")
        sys.exit(1)

    val selected = selectTargets(serviceArg)

    try selected.foreach(t => if dryRun then checkPending(t) else migrate(t))
    catch
      case t: Throwable =>
        System.err.println(s"versola-tools migrate: failed -- ${t.getMessage}")
        t.printStackTrace()
        sys.exit(1)
