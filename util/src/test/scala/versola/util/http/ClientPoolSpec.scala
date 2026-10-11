package versola.util.http

import zio.*
import zio.http.*
import zio.test.*

/** The outbound client's pool is what bounds a pod's throughput towards one backend: an HTTP/1.1
  * connection serves one request at a time, so `n` connections give `n / latency` requests per
  * second and the rest queue. zio-http's default is ten.
  */
object ClientPoolSpec extends ZIOSpecDefault:

  private val handlerDelay = 200.millis
  private val concurrent = 40

  private val routes = Routes(Method.GET / "slow" -> handler(ZIO.sleep(handlerDelay).as(Response.text("ok"))))

  /** Wall-clock time for `concurrent` simultaneous requests to a backend that takes `handlerDelay`
    * through a client with `poolSize` connections.
    */
  private def elapsed(poolSize: Int): ZIO[Any, Throwable, Duration] =
    ZIO.scoped((for
      port <- Server.install(routes)
      client <- Observability.pooledClient(poolSize).build.map(_.get[Client])
      url = URL.decode(s"http://localhost:$port/slow").toOption.get
      get = ZIO.scoped(client.request(Request.get(url))).flatMap(_.body.asString)
      _ <- get // connection set-up outside the timing
      started <- Clock.nanoTime
      _ <- ZIO.foreachParDiscard(1 to concurrent)(_ => get)
      done <- Clock.nanoTime
    yield Duration.fromNanos(done - started)).provideSome[Scope](Server.defaultWith(_.port(0))))

  def spec = suite("Observability client pool")(
    test("the default is 128 connections per target, not zio-http's 10") {
      assertTrue(
        Observability.defaultClientPoolSize == 128,
        // The value the layer is built with. It was 0 once: the default was declared below the val that reads it.
        Observability.clientPoolSize == 128,
        Observability.clientConfig(Observability.defaultClientPoolSize).connectionPool == ConnectionPoolConfig.Fixed(128),
        Observability.clientConfig(7).connectionPool == ConnectionPoolConfig.Fixed(7),
      )
    },
    // 40 requests of 200 ms: ten connections need four rounds, 64 need one. The gap is the queueing the
    // step-load test saw as a ~1 s p99 at 780 rps per pod.
    test("a pool smaller than the concurrency queues the requests, a larger one does not") {
      for
        small <- elapsed(10)
        large <- elapsed(64)
      yield assertTrue(
        small >= handlerDelay * 3.5,
        large < handlerDelay * 2.5,
        small > large,
      )
    } @@ TestAspect.withLiveClock,
  ) @@ TestAspect.timeout(60.seconds) @@ TestAspect.sequential
