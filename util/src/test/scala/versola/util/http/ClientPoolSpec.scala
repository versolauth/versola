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
    * through a client with the given pool.
    */
  private def elapsed(pool: ConnectionPoolConfig): ZIO[Any, Throwable, Duration] =
    ZIO.scoped((for
      port <- Server.install(routes)
      client <- Observability.pooledClient(pool).build.map(_.get[Client])
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
    test("no environment means the old Fixed(128); an unknown kind or invalid values fall back") {
      def pool(kind: String = null, size: String = null, min: String = null, ttl: String = null) =
        Observability.poolConfig(Option(kind), Option(size), Option(min), Option(ttl))
      assertTrue(
        pool() == ConnectionPoolConfig.Fixed(128),
        pool(kind = "fixed", size = "16") == ConnectionPoolConfig.Fixed(16),
        pool(kind = " FIXED ") == ConnectionPoolConfig.Fixed(128),
        // min and ttl only concern dynamic pools
        pool(size = "16", min = "4", ttl = "5s") == ConnectionPoolConfig.Fixed(16),
        pool(kind = "per-host", size = "16") == ConnectionPoolConfig.Fixed(16),
        pool(size = "abc") == ConnectionPoolConfig.Fixed(128),
        pool(size = "0") == ConnectionPoolConfig.Fixed(128),
        pool(size = "-3") == ConnectionPoolConfig.Fixed(128),
      )
    },
    test("dynamic: size is the maximum, minimum defaults to 0, ttl to 30s") {
      def pool(size: String = null, min: String = null, ttl: String = null) =
        Observability.poolConfig(Some("dynamic"), Option(size), Option(min), Option(ttl))
      assertTrue(
        pool() == ConnectionPoolConfig.Dynamic(0, 128, 30.seconds),
        pool(size = "64", min = "8", ttl = "2m") == ConnectionPoolConfig.Dynamic(8, 64, 2.minutes),
        pool(ttl = "500ms") == ConnectionPoolConfig.Dynamic(0, 128, 500.millis),
        pool(ttl = "45 seconds") == ConnectionPoolConfig.Dynamic(0, 128, 45.seconds),
        pool(size = "64", min = "64") == ConnectionPoolConfig.Dynamic(64, 64, 30.seconds),
      )
    },
    test("dynamic: invalid values fall back to their defaults") {
      def pool(size: String = null, min: String = null, ttl: String = null) =
        Observability.poolConfig(Some("dynamic"), Option(size), Option(min), Option(ttl))
      assertTrue(
        pool(size = "x") == ConnectionPoolConfig.Dynamic(0, 128, 30.seconds),
        pool(size = "0") == ConnectionPoolConfig.Dynamic(0, 128, 30.seconds),
        pool(min = "-1") == ConnectionPoolConfig.Dynamic(0, 128, 30.seconds),
        pool(min = "many") == ConnectionPoolConfig.Dynamic(0, 128, 30.seconds),
        // a minimum above the maximum would be an empty range
        pool(size = "10", min = "11") == ConnectionPoolConfig.Dynamic(0, 10, 30.seconds),
        pool(ttl = "soon") == ConnectionPoolConfig.Dynamic(0, 128, 30.seconds),
        pool(ttl = "0s") == ConnectionPoolConfig.Dynamic(0, 128, 30.seconds),
        pool(ttl = "-5s") == ConnectionPoolConfig.Dynamic(0, 128, 30.seconds),
        pool(ttl = "Inf") == ConnectionPoolConfig.Dynamic(0, 128, 30.seconds),
        pool(ttl = "") == ConnectionPoolConfig.Dynamic(0, 128, 30.seconds),
      )
    },
    // 40 requests of 200 ms: ten connections need four rounds, 64 need one. The gap is the queueing the
    // step-load test saw as a ~1 s p99 at 780 rps per pod.
    test("a pool smaller than the concurrency queues the requests, a larger one does not") {
      for
        small <- elapsed(ConnectionPoolConfig.Fixed(10))
        large <- elapsed(ConnectionPoolConfig.Fixed(64))
      yield assertTrue(
        small >= handlerDelay * 3.5,
        large < handlerDelay * 2.5,
        small > large,
      )
    } @@ TestAspect.withLiveClock,
    // A dynamic pool queues the same way once its maximum is below the concurrency, rather than failing.
    test("a dynamic pool with a maximum below the concurrency queues the requests, a larger one does not") {
      for
        small <- elapsed(ConnectionPoolConfig.Dynamic(0, 10, 30.seconds))
        large <- elapsed(ConnectionPoolConfig.Dynamic(0, 64, 30.seconds))
      yield assertTrue(
        small >= handlerDelay * 3.5,
        large < handlerDelay * 2.5,
        small > large,
      )
    } @@ TestAspect.withLiveClock,
  ) @@ TestAspect.timeout(60.seconds) @@ TestAspect.sequential
