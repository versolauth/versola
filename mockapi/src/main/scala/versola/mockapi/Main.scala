package versola.mockapi

import zio.*
import zio.http.*
import zio.metrics.connectors.MetricsConfig
import zio.metrics.connectors.prometheus.{PrometheusPublisher, prometheusLayer, publisherLayer}

/** Entry point for the load emulator's mock protected-resource backend (see
  * `versola-loadgen-dev-spec.md` §9). Deliberately does not extend `VersolaApp` or depend on
  * `util`: `VersolaApp` puts `Observability.middleware` in front of every route, which costs a
  * span, the RED counters, request/response serialisation and one unfiltered `receive-http` JSON
  * log line per request. At this service's ~8,400 rps that is a constant latency bias on the one
  * process whose entire purpose is to not distort the measurement -- and a bias the track L
  * calibration gate would then have to unpick. See build.sbt's comment on the `mockapi` project
  * for what that costs in exchange (this file, rather than `VersolaApp`, owns the boot sequence).
  *
  * No access logging and no per-request middleware on the business routes for the same reason:
  * the only work a business request does is one array lookup, one `ZIO.sleep`, a counter and a
  * histogram update.
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

  private val rootRoutes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "" -> handler { (_: Request) =>
        ZIO.succeed(Response.text("mockapi"))
      },
    )

  private val selfCheckDraws: Int = 1000000

  private val selfCheckTolerance: Double = 0.10

  /** `/readiness` answers 503 until the application listener is actually installed, matching what
    * `VersolaApp` does: a probe that reported ready earlier than that would route traffic at a
    * port nothing is bound to yet.
    */
  private def diagnosticsRoutes(ready: Ref[Boolean], publisher: PrometheusPublisher): Routes[Any, Nothing] =
    Routes(
      Method.GET / "metrics" -> handler { (_: Request) =>
        publisher.get.map(Response.text(_))
      },
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

  /** A miscalibrated backend does not fail visibly: every request still returns 200, and the
    * only symptom is that every latency conclusion the campaign draws about edge and auth is
    * measured against a reference that is not the one the report claims. A run like that is
    * discovered, if at all, days later and has to be repeated in full. So a failed self-check
    * aborts startup: in Kubernetes that is a CrashLoopBackOff before any traffic is generated,
    * which is the cheapest possible moment to find out. A warning in the log is not an option --
    * the log of a 72-hour campaign is exactly where a warning goes unread.
    */
  private def selfCheck(profile: DelayProfile, sampler: DelaySampler): Task[Unit] =
    for
      achieved <- ZIO.succeed(DelaySampler.sampleQuantiles(sampler, selfCheckDraws))
      targets = DelaySampler.targetsFor(profile)
      _ <- ZIO.logInfo(
        f"mockapi delay self-check ($profile, $selfCheckDraws draws): " +
          f"p50 ${achieved.p50Millis}%.2f ms (target ${targets.p50Millis}%.2f), " +
          f"p90 ${achieved.p90Millis}%.2f ms, " +
          f"p95 ${achieved.p95Millis}%.2f ms (target ${targets.p95Millis}%.2f), " +
          f"p99 ${achieved.p99Millis}%.2f ms (target ${targets.p99Millis}%.2f), " +
          f"mean ${achieved.meanMillis}%.2f ms",
      )
      failures = DelaySampler.deviations(achieved, targets, selfCheckTolerance)
      _ <- ZIO
        .fail(new IllegalStateException(s"mockapi delay self-check failed for $profile: ${failures.mkString("; ")}"))
        .when(failures.nonEmpty)
    yield ()

  // `zipPar`, not `fork`: a bind failure on either port has to take the process down. Forking the
  // diagnostics server and dropping its fiber would leave a live application server with no
  // liveness or readiness surface at all -- a pod that never gets restarted and never gets
  // traffic, which during a campaign reads as capacity that silently isn't there.
  private val program: ZIO[PrometheusPublisher, Throwable, Unit] =
    for
      readSampler <- ZIO.succeed(DelaySampler.make(MixtureWeights.read))
      writeSampler <- ZIO.succeed(DelaySampler.make(MixtureWeights.write))
      _ <- selfCheck(DelayProfile.Read, readSampler)
      _ <- selfCheck(DelayProfile.Write, writeSampler)
      samplers = (profile: DelayProfile) =>
        profile match
          case DelayProfile.Read => readSampler
          case DelayProfile.Write => writeSampler
      publisher <- ZIO.service[PrometheusPublisher]
      ready <- Ref.make(false)
      _ <- serve("diagnostics", diagnosticsPort, diagnosticsRoutes(ready, publisher), ZIO.unit)
        .zipPar(serve("application", port, rootRoutes ++ Endpoints.routes(samplers), ready.set(true)))
    yield ()

  // The metric listener has to be composed explicitly rather than handed to `provide`: only the
  // publisher is a service the routes ask for, while `prometheusLayer` is a scoped side effect
  // (it installs the listener and its polling fiber) whose Unit output nothing depends on.
  private val metricsLayer: ZLayer[Any, Nothing, PrometheusPublisher] =
    (ZLayer.succeed(MetricsConfig(1.second)) >>> (publisherLayer >+> prometheusLayer))
      .map(env => ZEnvironment(env.get[PrometheusPublisher]))

  override val run: ZIO[Any, Throwable, Unit] =
    program.provide(metricsLayer)
