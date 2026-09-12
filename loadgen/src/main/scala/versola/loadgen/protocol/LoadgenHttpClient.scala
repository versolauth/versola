package versola.loadgen.protocol

import zio.http.netty.NettyConfig
import zio.http.{ConnectionPoolConfig, Decompression, DnsResolver, ZClient}
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

  /** Idle users are rows, not sockets (design doc §6.3): 256 pooled keep-alive connections per
    * host serve an entire modelled population, and the 60 s idle timeout is longer than the
    * backend's 50 ms tail but shorter than a k8s endpoint change.
    */
  val config: ZClient.Config =
    ZClient.Config.default
      .copy(
        connectionPool = ConnectionPoolConfig.Fixed(256),
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

  val live: ZLayer[Any, Throwable, zio.http.Client] =
    (ZLayer.succeed(config) ++ ZLayer.succeed(nettyConfig) ++ DnsResolver.default) >>> ZClient.live
