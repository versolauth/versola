package versola.mockapi

import zio.*
import zio.http.*

/** Entry point for the load emulator's mock protected-resource backend (see
  * `versola-loadgen-dev-spec.md` §9). Deliberately does not extend `VersolaApp` or depend on
  * `util`: `VersolaApp` puts `Observability.middleware` in front of every route, which costs a
  * span, the RED counters, request/response serialisation and one unfiltered `receive-http` JSON
  * log line per request. At this service's ~8,400 rps that is a constant latency bias on the one
  * process whose entire purpose is to not distort the measurement -- and a bias the track L
  * calibration gate would then have to unpick. See build.sbt's comment on the `mockapi` project
  * for what that costs in exchange (this file, rather than `VersolaApp`, owns the boot sequence).
  *
  * This is the skeleton commit only: it wires `mockapi` into the build with a plain
  * liveness/readiness surface. The ten business routes and `DelaySampler` land with Phase 1
  * track A.
  */
object Main extends ZIOAppDefault:

  // 8100/8101 is where the dev spec (§5's `targets.mock-url`) and Dockerfile.mockapi's EXPOSE
  // both put mockapi -- deliberately not VersolaApp's 8080/8081, which auth already holds in the
  // docker-local topology.
  private def port: Int =
    Option(java.lang.System.getenv("PORT")).flatMap(_.toIntOption).getOrElse(8100)

  private def diagnosticsPort: Int =
    Option(java.lang.System.getenv("DPORT")).flatMap(_.toIntOption).getOrElse(8101)

  // Same default as VersolaApp's bindHost -- see that class's comment on why 0.0.0.0.
  private def bindHost: String =
    Option(java.lang.System.getenv("BIND_HOST")).getOrElse("0.0.0.0")

  private val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "" -> handler { (_: Request) =>
        ZIO.succeed(Response.text("mockapi"))
      },
    )

  /** `/readiness` answers 503 until the application listener is actually installed, matching what
    * `VersolaApp` does: a probe that reported ready earlier than that would route traffic at a
    * port nothing is bound to yet.
    */
  private def diagnosticsRoutes(ready: Ref[Boolean]): Routes[Any, Nothing] =
    Routes(
      Method.GET / "liveness" -> handler { (_: Request) => ZIO.succeed(Response.ok) },
      Method.GET / "readiness" -> handler { (_: Request) =>
        ready.get.map(if _ then Response.ok else Response.status(Status.ServiceUnavailable))
      },
    )

  private def serve(
      name: String,
      boundPort: Int,
      serverRoutes: Routes[Any, Nothing],
      onInstalled: UIO[Unit],
  ): ZIO[Any, Throwable, Nothing] =
    (
      Server.install(serverRoutes) *>
        ZIO.logInfo(s"mockapi $name server started on port $boundPort") *>
        onInstalled *>
        ZIO.never
    ).provide(
      Server.live,
      ZLayer.succeed(Server.Config.default.binding(bindHost, boundPort)),
    )

  // `zipPar`, not `fork`: a bind failure on either port has to take the process down. Forking the
  // diagnostics server and dropping its fiber would leave a live application server with no
  // liveness or readiness surface at all -- a pod that never gets restarted and never gets
  // traffic, which during a campaign reads as capacity that silently isn't there.
  override val run: ZIO[Any, Throwable, Unit] =
    for
      ready <- Ref.make(false)
      _ <- serve("diagnostics", diagnosticsPort, diagnosticsRoutes(ready), ZIO.unit)
        .zipPar(serve("application", port, routes, ready.set(true)))
    yield ()
