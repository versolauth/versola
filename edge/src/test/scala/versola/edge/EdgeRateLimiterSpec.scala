package versola.edge

import versola.edge.model.EdgeId
import versola.util.Secret
import zio.*
import zio.http.URL
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

import java.security.KeyPairGenerator

/** The wiring of the limiter's pod count: what `replicas-from` resolves wins over the policy's `replicas`, and a bad setting
  * stops the edge at start instead of being ignored. The counting itself is [[ReplicaCounterSpec]].
  */
object EdgeRateLimiterSpec extends ZIOSpecDefault:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private def config(replicasFrom: Option[String]) = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "edge-key",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(1.toByte))),
      EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
    replicasFrom = replicasFrom,
  )

  private def gauge(source: String): UIO[Double] =
    Metric.gauge("edge_rate_limit_replicas").tagged(MetricLabel("source", source)).value.map(_.value)

  /** Starts the limiter the way the edge does and keeps it running while `effect` looks at the gauge. */
  private def running[A](replicasFrom: Option[String], policySetReplicas: Int)(effect: UIO[A]): ZIO[Any, Throwable, A] =
    val source = new RateLimitPolicySource:
      override val current = ZIO.succeed(Nil)
      override val replicas = ZIO.succeed(policySetReplicas)
    ZIO.scoped:
      EdgeRateLimiter.live.build.provideSomeEnvironment[Scope](_.add(config(replicasFrom)).add[RateLimitPolicySource](source)) *> effect

  /** The applier publishes once a second; wait for the first publication. */
  private def settled(source: String, expected: Double) =
    gauge(source).repeatUntil(_ == expected).timeout(5.seconds)

  def spec = suite("EdgeRateLimiter")(
    test("without replicas-from the policy's replicas are in force") {
      running(None, 4)(settled("policy", 4.0)).map(seen => assertTrue(seen.contains(4.0)))
    },
    test("replicas-from = dns:<name> puts the resolved count in force, whatever the policy says") {
      // localhost resolves to one pod; the policy set claims 7
      running(Some("dns:localhost"), 7)(settled("dns", 1.0)).map(seen => assertTrue(seen.contains(1.0)))
    },
    test("a replicas-from that is not dns:<name> stops the edge at start") {
      running(Some("kubernetes:edge"), 1)(ZIO.unit).exit
        .map(exit => assertTrue(exit.causeOption.exists(_.defects.exists(_.getMessage.contains("dns:<name>")))))
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
