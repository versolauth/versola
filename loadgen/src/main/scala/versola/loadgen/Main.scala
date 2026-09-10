package versola.loadgen

import versola.util.EnvName
import versola.util.http.VersolaApp
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

/** Entry point for both the coordinator and driver roles (see `versola-loadgen-dev-spec.md`
  * §2, §12) -- which one this process plays is a config value (`role = coordinator | driver`),
  * not a build-time or CLI switch, so the same staged jar and image serve both.
  *
  * This is the skeleton commit only: it wires `loadgen` into the build and proves out
  * `VersolaApp`'s boot sequence (diagnostics port, `/liveness`, `/readiness`, graceful shutdown)
  * for the module. Role dispatch, the full `LoadgenConfig` tree, and the real routes land with
  * the `config`/`protocol`/`coordinator`/`driver` packages.
  */
object Main extends VersolaApp("loadgen"):
  val environmentTag = Tag[Environment]

  override type Dependencies = Any

  override given Tag[Dependencies] = Tag[Dependencies]

  override val dependencies: ZLayer[Scope & EnvName & ConfigProvider & Tracing & Client, Throwable, Dependencies] =
    ZLayer.succeed(())

  override def routes: Routes[Dependencies & Tracing & EnvName, Throwable] =
    Routes(
      Method.GET / "" -> handler { (_: Request) =>
        ZIO.succeed(Response.text("loadgen"))
      },
    )
