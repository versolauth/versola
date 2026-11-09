package versola.util.http

import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.test.*

/** The shared client bounds how long a connection may take to open and how long a response body may take to read. Without the second,
  * the only bound on a body that drips is the client's 50 s idle timeout, which every byte restarts.
  */
object ClientTimeoutSpec extends ZIOSpecDefault:

  /** Twenty bytes, one every 300 ms: about six seconds, never silent for more than 300 ms. */
  private val drip = ZStream.repeatZIOWithSchedule(ZIO.succeed('x'.toByte), Schedule.spaced(300.millis)).take(20)

  private val routes = Routes(
    Method.GET / "drip" -> handler(Response(status = Status.Ok, body = Body.fromStreamChunked(drip))),
    Method.GET / "quick" -> handler(Response.text("ok")),
  )

  /** What reading `path` through a client with this configuration gives, and how long the request and the read took. The time is taken
    * after the server and the client are built: on a loaded runner building them (a Netty event loop, the DNS resolver) took seconds,
    * and a test of the read timeout must not measure that.
    */
  private def read(config: ZClient.Config, path: String): ZIO[Any, Nothing, (Either[Throwable, String], Duration)] =
    ZIO.scoped((for
      port <- Server.install(routes)
      client <- (ZLayer.succeed(config) ++ ZLayer.succeed(zio.http.netty.NettyConfig.defaultWithFastShutdown) ++ DnsResolver.default >>>
        ZClient.live).build.map(_.get[Client])
      url = URL.decode(s"http://localhost:$port/$path").toOption.get
      started <- Clock.nanoTime
      body <- ZIO.scoped(client.request(Request.get(url)).flatMap(_.body.asString)).either
      took <- Clock.nanoTime.map(done => Duration.fromNanos(done - started))
    yield (body, took)).provideSome[Scope](Server.defaultWith(_.port(0)))).orDie

  def spec = suite("Observability client timeouts")(
    test("a body that keeps dripping is cut at the body read timeout, though it is never silent for that long") {
      read(Observability.clientConfig(10).copy(bodyReadTimeout = Some(1.second)), "drip").map: (body, took) =>
        assertTrue(
          body.isLeft,
          took < 4.seconds, // the body would take about six
        )
    },
    test("without the timeout the same body is read to the end (the 50 s idle timeout is restarted by every byte)") {
      read(Observability.clientConfig(10).copy(bodyReadTimeout = None), "drip").map((body, _) => assertTrue(body == Right("x" * 20)))
    },
    test("a body that arrives within the timeout is read") {
      read(Observability.clientConfig(10).copy(bodyReadTimeout = Some(1.second)), "quick").map((body, _) => assertTrue(body == Right("ok")))
    },
    test("the shared client is configured with both bounds, 5 s to connect and 30 s to read a body, unless the environment says otherwise") {
      val config = Observability.clientConfig(10)
      assertTrue(
        Observability.defaultClientConnectTimeout == 5.seconds,
        Observability.defaultClientBodyReadTimeout == 30.seconds,
        config.connectionTimeout == Observability.clientConnectTimeout,
        config.bodyReadTimeout == Observability.clientBodyReadTimeout,
        config.connectionTimeout.nonEmpty == sys.env.get("HTTP_CLIENT_CONNECT_TIMEOUT").forall(_.trim.toLowerCase != "off"),
        config.bodyReadTimeout.nonEmpty == sys.env.get("HTTP_CLIENT_BODY_READ_TIMEOUT").forall(_.trim.toLowerCase != "off"),
      )
    },
    test("unset means the default, off removes the bound, a duration replaces it, and an invalid value falls back to the default") {
      def timeout(raw: String = null) = Observability.clientTimeout(Option(raw), 30.seconds)
      assertTrue(
        timeout() == Some(30.seconds),
        timeout("off") == None,
        timeout(" OFF ") == None,
        timeout("10s") == Some(10.seconds),
        timeout("500ms") == Some(500.millis),
        timeout("2 minutes") == Some(2.minutes),
        timeout("soon") == Some(30.seconds),
        timeout("") == Some(30.seconds),
        timeout("0s") == Some(30.seconds),
        timeout("-5s") == Some(30.seconds),
        timeout("Inf") == Some(30.seconds),
      )
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
