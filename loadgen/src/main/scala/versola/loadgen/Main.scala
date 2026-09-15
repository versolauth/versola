package versola.loadgen

import versola.loadgen.config.{LoadgenConfig, LoadgenRole}
import versola.loadgen.coordinator.{Coordinator, CoordinatorRoutes, CoordinatorService}
import versola.loadgen.provision.Provisioner
import versola.loadgen.seed.Seeder
import versola.util.EnvName
import versola.util.http.VersolaApp
import zio.*
import zio.config.magnolia.deriveConfig
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

/** Entry point for both the coordinator and driver roles (see `versola-loadgen-dev-spec.md`
  * §2, §12) -- which one this process plays is a config value (`role = coordinator | driver`),
  * not a build-time or CLI switch, so the same staged jar and image serve both.
  *
  * `role = seed` and `role = provision` are the one-shot subcommands of §10 and §4: each runs to
  * completion and exits, so they take over `run` rather than standing up the servers `VersolaApp`
  * otherwise waits on forever. The remaining roles still land with the `coordinator`/`driver`
  * packages.
  */
object Main extends VersolaApp("loadgen"):
  val environmentTag = Tag[Environment]

  /** `Option`, because the coordinator's service is the one dependency exactly one role has: a
    * driver process must not open the store pool the coordinator's plan service holds, and the
    * dependency layer is built before `routes` for every role that reaches it.
    */
  override type Dependencies = Option[CoordinatorService]

  override given Tag[Dependencies] = Tag[Dependencies]

  override val dependencies: ZLayer[Scope & EnvName & ConfigProvider & Tracing & Client, Throwable, Dependencies] =
    ZLayer:
      ZIO.serviceWithZIO[ConfigProvider](_.load(deriveConfig[LoadgenConfig])).flatMap: config =>
        config.role match
          case LoadgenRole.Coordinator => Coordinator.make(config).asSome
          case _ => ZIO.none

  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] =
    ZIO.serviceWithZIO[ConfigProvider](_.load(deriveConfig[LoadgenConfig])).flatMap: config =>
      config.role match
        case LoadgenRole.Provision => Provisioner.provision(config)
        case LoadgenRole.Seed => Seeder.seed(config)
        case _ => super.run

  override def routes: Routes[Dependencies & Tracing & EnvName, Throwable] =
    Routes(
      Method.GET / "" -> handler { (_: Request) =>
        ZIO.succeed(Response.text("loadgen"))
      },
    ) ++ CoordinatorRoutes.routes
