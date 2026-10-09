package versola.loadgen.driver

import versola.loadgen.config.TargetsConfig
import versola.loadgen.protocol.DpopKeyPool
import zio.*
import zio.http.*
import zio.test.*

object WarmupSpec extends ZIOSpecDefault:

  private val targets = TargetsConfig("http://auth.test", "http://edge.test", "http://mock.test", "https://origin.test")

  private def recording(seen: Ref[Vector[String]]): Routes[Any, Nothing] =
    Routes(
      Method.GET / ".well-known" / "openid-configuration" -> handler(seen.update(_ :+ "discovery").as(Response.json("{}"))),
      Method.GET / "resources" / "" -> handler(seen.update(_ :+ "resources").as(Response.status(Status.Unauthorized))),
    )

  def spec = suite("Warmup")(
    test("asks auth's discovery document and edge's resource root, several rounds, and signs proofs when DPoP is on") {
      for
        seen <- Ref.make(Vector.empty[String])
        _ <- TestClient.addRoutes(recording(seen))
        client <- ZIO.service[Client]
        pool <- DpopKeyPool.derive("warmup-spec", 1)
        _ <- Warmup.run(client, targets, Some(pool))
        calls <- seen.get
      yield assertTrue(
        calls.count(_ == "discovery") == Warmup.rounds,
        calls.count(_ == "resources") == Warmup.rounds,
      )
    },
    // The point of it is to have warmed what could be warmed, not to gate the campaign on the SUT.
    test("a SUT that answers nothing does not fail the driver") {
      for
        client <- ZIO.service[Client]
        done <- Warmup.run(client, targets, None).exit
      yield assertTrue(done.isSuccess)
    },
    test("asks only for things that need no credential and change nothing") {
      assertTrue(Warmup.urls(targets) == List("http://auth.test/.well-known/openid-configuration", "http://edge.test/resources/"))
    },
  ).provide(TestClient.layer) @@ TestAspect.sequential
