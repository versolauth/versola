package versola.edge

import zio.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

object ReplicaCounterSpec extends ZIOSpecDefault:

  private def gauge(source: String): UIO[Double] =
    Metric.gauge("edge_rate_limit_replicas").tagged(MetricLabel("source", source)).value.map(_.value)

  def spec = suite("ReplicaCounter")(
    suite("the setting")(
      test("dns:<name> names what to resolve; anything else is refused with the reason") {
        assertTrue(
          ReplicaCounter.parse("dns:versola-edge-headless") == Right("versola-edge-headless"),
          ReplicaCounter.parse("  dns:edge.versola.svc  ") == Right("edge.versola.svc"),
          ReplicaCounter.parse("dns:").isLeft,
          ReplicaCounter.parse("dns: a b").isLeft,
          ReplicaCounter.parse("dns:http://edge").isLeft,
          ReplicaCounter.parse("kubernetes:edge").left.exists(_.contains("dns:<name>")),
          ReplicaCounter.parse("").isLeft,
        )
      },
    ),
    suite("counting")(
      test("distinct addresses, one family: a dual-stack service lists a pod twice") {
        assertTrue(
          ReplicaCounter.count(List("10.0.0.1", "10.0.0.2", "10.0.0.3")) == 3,
          ReplicaCounter.count(List("10.0.0.1", "10.0.0.1")) == 1,
          ReplicaCounter.count(List("10.0.0.1", "fd00::1", "10.0.0.2", "fd00::2")) == 2,
          ReplicaCounter.count(List("fd00::1", "fd00::2")) == 2,
        )
      },
    ),
    suite("resolving")(
      test("nothing is known before the first answer; then the count follows the answers") {
        for
          answers <- Ref.make[Task[List[String]]](ZIO.succeed(List("10.0.0.1", "10.0.0.2")))
          counter <- ReplicaCounter.dns("edge", _ => answers.get.flatten, 10.seconds)
          before <- counter
          _ <- TestClock.adjust(1.second)
          first <- counter
          _ <- answers.set(ZIO.succeed(List("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4")))
          _ <- TestClock.adjust(10.seconds)
          grown <- counter
          _ <- answers.set(ZIO.succeed(List("10.0.0.1")))
          _ <- TestClock.adjust(10.seconds)
          shrunk <- counter
        yield assertTrue(before.isEmpty, first.contains(2), grown.contains(4), shrunk.contains(1))
      },
      test("a failed or empty answer keeps the last count: a glitch must not change the share") {
        for
          answers <- Ref.make[Task[List[String]]](ZIO.succeed(List("10.0.0.1", "10.0.0.2", "10.0.0.3")))
          counter <- ReplicaCounter.dns("edge", _ => answers.get.flatten, 10.seconds)
          _ <- TestClock.adjust(1.second)
          good <- counter
          _ <- answers.set(ZIO.fail(RuntimeException("SERVFAIL")))
          _ <- TestClock.adjust(10.seconds)
          failed <- counter
          _ <- answers.set(ZIO.succeed(Nil))
          _ <- TestClock.adjust(10.seconds)
          empty <- counter
          _ <- answers.set(ZIO.succeed(List("10.0.0.1")))
          _ <- TestClock.adjust(10.seconds)
          recovered <- counter
        yield assertTrue(good.contains(3), failed.contains(3), empty.contains(3), recovered.contains(1))
      },
      test("a failure before any answer leaves nothing known: the policy's number applies") {
        for
          counter <- ReplicaCounter.dns("edge", _ => ZIO.fail(RuntimeException("no such host")), 10.seconds)
          _ <- TestClock.adjust(1.second)
          known <- counter
        yield assertTrue(known.isEmpty)
      },
    ).provideSomeLayer[Scope](ZLayer.empty) @@ TestAspect.silentLogging,
    suite("the number applied")(
      test("the resolved count wins over the policy's; without one the policy's is used; never below 1") {
        for
          withDns <- ReplicaCounter.effective(ZIO.succeed(Some(5)), ZIO.succeed(2))
          withoutDns <- ReplicaCounter.effective(ZIO.succeed(None), ZIO.succeed(2))
          floor <- ReplicaCounter.effective(ZIO.succeed(None), ZIO.succeed(0))
          dnsGauge <- gauge("dns")
          policyGauge <- gauge("policy")
        yield assertTrue(withDns == 5, withoutDns == 2, floor == 1, dnsGauge == 5.0, policyGauge == 1.0)
      },
    ),
  ) @@ TestAspect.sequential
