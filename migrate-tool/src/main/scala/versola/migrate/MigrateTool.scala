package versola.migrate

import com.typesafe.config.ConfigFactory
import versola.util.postgres.PostgresHikariDataSource
import zio.*
import zio.config.typesafe.FromConfigSourceTypesafe

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
  * Ships as a jar inside versola-tools (reusing `util`/`util-postgres` -- see build.sbt's
  * `migrate-tool` project) rather than as three separate one-shot invocations of
  * auth/central/edge's own images, precisely so it can bundle all three services' migrations
  * together without any of them needing to know about the others (see
  * `PostgresHikariDataSource.transactor`'s own comment on why `migrationLocations` must be passed
  * explicitly here rather than relying on auto-detection).
  */
object MigrateTool extends ZIOAppDefault:

  private case class Target(serviceName: String, configPath: String, migrationsLocation: String)

  // Matches wherever compose.fragment.yml.template / .vps.yml.template mount the generated
  // auth.conf/central.conf/edge.conf inside this image -- see docker/Dockerfile.tools' WORKDIR
  // (/opt/versola-tools). Overridable via CONFIG_DIR for anyone invoking this image directly
  // instead of through those compose fragments.
  private val configDir = Option(java.lang.System.getenv("CONFIG_DIR")).getOrElse("/opt/versola-tools/config")

  private val targets = List(
    Target("auth", s"$configDir/auth.conf", "filesystem:./auth/implementations/postgres/migrations"),
    Target("central", s"$configDir/central.conf", "filesystem:./central/implementations/postgres/migrations"),
    Target("edge", s"$configDir/edge.conf", "filesystem:./edge/implementations/postgres/migrations"),
  )

  /** Builds a ConfigProvider straight from a mounted .conf file, the same shape VersolaApp's own
    * `configProvider` builds from `ENV_CONFIG`/`env.path` -- `.kebabCase` matters here for the
    * same reason it does there: `PostgresConfig`'s fields (`maximumPoolSize`, etc.) are looked up
    * against the config's kebab-case HOCON keys (`maximum-pool-size`, etc.).
    *
    * Deliberately not `ConfigFactory.parseFile(...).resolve()` without first checking the file
    * exists -- typesafe-config silently returns an *empty* config for a missing file rather than
    * failing, which would otherwise surface here as an opaque "postgres.url is not set" instead of
    * naming the actual missing mount.
    */
  private def configProvider(path: String): Task[ConfigProvider] =
    for
      file <- ZIO.succeed(new File(path))
      _ <- ZIO
        .fail(new java.io.FileNotFoundException(s"Config file not found: $path"))
        .unless(file.isFile)
      typesafe <- ZIO.attempt(ConfigFactory.parseFile(file).resolve())
      cp <- ConfigProvider.fromTypesafeConfigZIO(typesafe).map(_.kebabCase)
    yield cp

  private def migrate(target: Target): Task[Unit] =
    for
      _ <- ZIO.logInfo(s"${target.serviceName}: applying migrations from ${target.configPath}")
      cp <- configProvider(target.configPath)
      _ <- ZIO
        .scoped {
          PostgresHikariDataSource
            .transactor(
              serviceName = Some(target.serviceName),
              migrate = true,
              migrationLocations = Some(List(target.migrationsLocation)),
            )
            .build
        }
        .provide(ZLayer.succeed(cp))
      _ <- ZIO.logInfo(s"${target.serviceName}: migrations complete")
    yield ()

  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] =
    ZIO
      .foreachDiscard(targets)(migrate)
      .tapErrorCause(cause => ZIO.logErrorCause("versola-tools migrate: failed", cause))
