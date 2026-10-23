package versola.migrate

import com.typesafe.config.{ConfigException, ConfigFactory, ConfigResolveOptions}
import org.flywaydb.core.Flyway

import java.io.File
import java.sql.SQLException
import scala.annotation.tailrec
import scala.util.Random

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
  * Only `postgres.url`/`postgres.user`/`postgres.password` are ever read, and only those have to
  * resolve: every other `${VAR}` placeholder in the file may stay unset. A one-shot Job therefore
  * needs the Postgres credentials and nothing else -- not the token, signing or client secrets
  * the same files reference for the running services.
  *
  * Tuning, all from the environment so the Job can set them without a new flag:
  * `MIGRATE_LOCK_TIMEOUT_MS` (default 5000, 0 disables) is set as the session's `lock_timeout`, so
  * a DDL statement waiting behind a long transaction of the version still serving traffic gives up
  * instead of queueing every query behind it; `MIGRATE_LOCK_RETRIES` (default 3) is how many times
  * a service's migration is retried after such a timeout; `MIGRATE_CONNECT_RETRIES` (default 3)
  * covers Postgres not accepting connections yet.
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
  private case class PgConnection(url: String, user: String, password: String, sslRootCert: Option[String])

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
    // Unresolved placeholders are tolerated at resolve time and only fail when a value is read, so
    // the keys below are the whole requirement. Resolving the document strictly would demand every
    // secret the three files reference, which this process has no use for.
    val conf = ConfigFactory.parseFile(file).resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
    def read(key: String): String =
      try conf.getString(key)
      catch
        case e: ConfigException =>
          throw IllegalStateException(s"$path: cannot read '$key' (${e.getMessage}); set the environment variable it references", e)
    PgConnection(
      url = read("postgres.url"),
      user = read("postgres.user"),
      password = read("postgres.password"),
      sslRootCert = Option.when(conf.hasPath("postgres.ssl-root-cert"))(read("postgres.ssl-root-cert")),
    )

  /** The URL as the services themselves would connect with it. They verify the database's
    * certificate (`sslmode=verify-full`, see `PostgresTls`), against `postgres.ssl-root-cert` when
    * the config names one -- a plain pgjdbc URL does neither, so without this the migrations of a
    * deployment with its own CA would run over a connection nothing verified. Each of the two is
    * added only when the URL does not carry its own, independently of the other, as for the
    * services: a URL with `sslmode=verify-full` and no `sslrootcert` is the common form, and
    * leaving the CA out there made pgjdbc look for `~/.postgresql/root.crt` instead. Without a CA
    * in the config this tool keeps connecting exactly as before.
    */
  private def withTls(connection: PgConnection): String =
    connection.sslRootCert match
      case None => connection.url
      case Some(path) =>
        val missing = List(
          Option.when(!connection.url.contains("sslmode="))("sslmode=verify-full"),
          Option.when(!connection.url.contains("sslrootcert="))(s"sslrootcert=$path"),
        ).flatten
        if missing.isEmpty then connection.url
        else
          val separator = if connection.url.contains("?") then "&" else "?"
          s"${connection.url}$separator${missing.mkString("&")}"

  private def intEnv(name: String, default: Int): Int =
    Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty) match
      case None => default
      case Some(v) =>
        v.toIntOption.filter(_ >= 0).getOrElse:
          System.err.println(s"versola-tools migrate: $name must be a non-negative integer, got '$v'")
          sys.exit(1)

  private val lockTimeoutMs = intEnv("MIGRATE_LOCK_TIMEOUT_MS", 5000)
  private val lockRetries = intEnv("MIGRATE_LOCK_RETRIES", 3)
  private val connectRetries = intEnv("MIGRATE_CONNECT_RETRIES", 3)

  /** Postgres' `lock_not_available`, what `lock_timeout` raises. */
  private val LockNotAvailable = "55P03"

  /** Gives the migration session a `lock_timeout`, through the driver's `options` startup
    * parameter, so it applies to every statement Flyway runs on it. The old version keeps serving
    * while this runs (see `.claude/rules/database-migrations.md`): a DDL statement that has to wait
    * for a lock queues every later query on that table behind itself, so waiting long is itself
    * an outage. Giving up instead is safe, the migration is one transaction and is retried (see
    * `withLockRetries`). A URL that sets its own `options` is the operator's choice and stays.
    */
  private def withLockTimeout(url: String): String =
    if lockTimeoutMs == 0 || url.contains("options=") then url
    else
      val separator = if url.contains("?") then "&" else "?"
      s"$url${separator}options=-c%20lock_timeout=$lockTimeoutMs"

  private def isLockTimeout(t: Throwable): Boolean =
    @tailrec def inChain(e: SQLException): Boolean =
      e != null && (e.getSQLState == LockNotAvailable || inChain(e.getNextException))
    @tailrec def inCauses(e: Throwable, depth: Int): Boolean =
      if e == null || depth >= 16 then false
      else
        e match
          case sql: SQLException if inChain(sql) => true
          case _ => inCauses(e.getCause, depth + 1)
    inCauses(t, 0)

  /** Retries `op` when Postgres refused a lock within `lock_timeout`, up to `lockRetries` times,
    * with a growing, jittered pause so two migrators (or a migrator and the traffic that held the
    * lock) do not collide again in step. Any other failure is raised at once: a broken migration is
    * not made better by another try.
    */
  private def withLockRetries[A](name: String)(op: => A): A =
    @tailrec def attempt(n: Int): A =
      val result: Either[Throwable, A] =
        try Right(op)
        catch case e: Throwable if isLockTimeout(e) && n < lockRetries => Left(e)
      result match
        case Right(a) => a
        case Left(_) =>
          val pauseMs = 1000L * (n + 1) + Random.nextInt(1000)
          println(s"$name: a lock was not available within ${lockTimeoutMs}ms, retry ${n + 1} of $lockRetries in ${pauseMs}ms")
          Thread.sleep(pauseMs)
          attempt(n + 1)
    attempt(0)

  /** Builds the one Flyway instance both `migrate` and `checkPending` need --
    * identical configuration either way, since a dry run has to validate
    * against the exact same migration history and connection a real one
    * would, or "what would apply" stops meaning anything.
    *
    * The single difference is `ignorePending`, for the dry run only: Flyway's
    * `validate()` reports a migration that simply hasn't been applied yet as
    * "Detected resolved migration not applied to database" -- exactly what a
    * dry run exists to list, so validating without it failed on every run that
    * had anything to show. `migrate()` itself never needs this: pending
    * migrations are what it applies, and its own pre-migrate validation already
    * treats them that way.
    */
  private def buildFlyway(target: Target, ignorePending: Boolean = false): Flyway =
    val connection = readConnection(target.configPath)
    val config = Flyway
      .configure()
      .locations(target.migrationsLocation)
      .dataSource(withLockTimeout(withTls(connection)), connection.user, connection.password)
      // Postgres not accepting connections yet (a restart in progress, a pod started a moment ahead
      // of its database): wait instead of failing the whole release over it.
      .connectRetries(connectRetries)
      .connectRetriesInterval(5)
      // Never disabled, unlike `PostgresHikariDataSource.layer`'s own `validateOnMigrate` (which
      // services can turn off in tests/development) -- this is the one place that actually
      // mutates a real schema, so there's no context where skipping Flyway's own pre-migrate
      // validation is the right call.
      .validateOnMigrate(true)
      .cleanDisabled(true)
      .validateMigrationNaming(false)
      // No `.ignoreMigrationPatterns(...)` override here (only the dry run adds `*:pending`, below),
      // unlike the services' own `validate()`
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
    // Replaces Flyway's default patterns rather than adding to them, so "*:future" (the default)
    // is listed again explicitly.
    if ignorePending then config.ignoreMigrationPatterns("*:future", "*:pending").load()
    else config.load()

  private def migrate(target: Target): Unit =
    println(s"${target.serviceName}: applying migrations from ${target.configPath}")
    val flyway = buildFlyway(target)
    withLockRetries(target.serviceName)(flyway.migrate())
    println(s"${target.serviceName}: migrations complete")

  /** `--dry-run`'s own path: validates the same way `migrate` would, then
    * lists what Flyway's own history table says is still pending, via
    * `info().pending()` -- standard OSS Flyway, not a Teams-only feature
    * (unlike `dryRunOutput`, which generates SQL and IS Teams-only) --
    * without ever calling `.migrate()`, so nothing here touches the schema.
    *
    * The validation is skipped when nothing has been applied yet. `validate()`
    * compares the resolved migrations against the schema history table, so on a
    * database this tool has never run against it fails with `Schema "x" doesn't
    * exist yet` -- an error about the absence of the very thing a first run is
    * supposed to create. That turned the preview mode off in exactly the case
    * it is most wanted: inspecting what a brand-new environment is about to
    * receive. Everywhere else the check is unchanged, and deliberately so: a
    * dry run against an environment that HAS a history is worth failing when
    * that history disagrees with the migrations on disk, which is the same
    * thing `migrate` would refuse to do.
    */
  private def checkPending(target: Target): Unit =
    println(s"${target.serviceName}: checking pending migrations against ${target.configPath}")
    val flyway = buildFlyway(target, ignorePending = true)
    val info = flyway.info()
    if info.applied().isEmpty then
      println(s"${target.serviceName}: no schema history yet, every migration below is a first-time apply")
    else flyway.validate()
    val pending = info.pending()
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
              s"versola-tools migrate: unknown --service '$name' (expected one of ${targets.map(_.serviceName).mkString(", ")})",
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
