package versola.loadgen.protocol

import versola.util.http.Observability
import zio.http.netty.NettyConfig
import zio.http.{ConnectionPoolConfig, Decompression, DnsResolver, ZClient}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Duration, ZLayer, durationInt}

/** The one `ZClient` a driver pod builds, shared by every fiber (versola-loadgen-dev-spec.md
  * §4). Field names verified against zio-http 3.6.0, as that section asks.
  *
  * Redirects are not followed -- zio-http's client never does -- which is what makes every hop
  * of §8 a separately measured step rather than one opaque chain.
  */
object LoadgenHttpClient:
  /** Anything slower than this is a failure, not a slow success. Applied per request by
    * [[HttpExchange]], since zio-http's `Config` has no whole-request deadline of its own --
    * `connectionTimeout` covers only establishing the connection.
    */
  val requestTimeout: Duration = 10.seconds

  /** Idle users are rows, not sockets (design doc §6.3): pooled keep-alive connections per host serve an
    * entire modelled population, and the 60 s idle timeout is longer than the backend's 50 ms tail but
    * shorter than a k8s endpoint change.
    *
    * The pool is also the driver's ceiling on in-flight requests: `size / latency` per host per driver, with
    * the excess queueing inside the driver and being recorded as latency the SUT never had. 256 is enough for
    * ~2500 rps at 100 ms across two drivers and not for more, so `HTTP_CLIENT_POOL_SIZE` (the variable the
    * services use) raises it; size it above `rate x latency` of the scale being driven. Unlike the services, whose
    * default pool is dynamic, the driver keeps a fixed pool so its ceiling does not move during a measurement; it reads
    * only the size, not `HTTP_CLIENT_POOL_KIND`.
    */
  val poolSize: Int =
    Option(java.lang.System.getenv("HTTP_CLIENT_POOL_SIZE")).flatMap(_.trim.toIntOption).filter(_ > 0).getOrElse(256)

  val config: ZClient.Config =
    ZClient.Config.default
      .copy(
        connectionPool = ConnectionPoolConfig.Fixed(poolSize),
        // The SUT does not compress; do not pay for the check.
        requestDecompression = Decompression.No,
        idleTimeout = Some(60.seconds),
        connectionTimeout = Some(10.seconds),
        addUserAgentHeader = false,
      )

  /** `min(4, cores)` event loop threads: the scenario fibers need the rest of the pod's CPU,
    * and a driver deliberately runs at ~30% so that the instrument does not distort the p99 it
    * is measuring (design doc §6.5).
    */
  val nettyConfig: NettyConfig =
    NettyConfig.default.maxThreads(math.min(4, java.lang.Runtime.getRuntime.availableProcessors()))

  /** `versola.http.HttpClient`'s `http_client_requests_total`/`http_client_request_duration_seconds`
    * (§11), the same pair every other service's outbound calls carry -- a driver's own connection
    * pool otherwise has no metric at all, only the coordinator-facing `loadgen_inflight_requests`
    * gauge, which says how many calls are in flight and nothing about how long they took or how
    * they ended.
    */
  val live: ZLayer[Tracing, Throwable, zio.http.Client] =
    ((ZLayer.succeed(config) ++ ZLayer.succeed(nettyConfig) ++ DnsResolver.default) >>> ZClient.live) ++
      ZLayer.service[Tracing] >>> ZLayer.fromFunction((client: zio.http.Client, tracing: Tracing) =>
        client @@ Observability.clientMiddleware(tracing),
      )
