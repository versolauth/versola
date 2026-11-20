package versola.util.http

import io.opentelemetry.api
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.net.{ServerSocket, Socket, SocketTimeoutException}
import java.util.concurrent.atomic.AtomicInteger

/** #579: what the shared client does with a pooled connection the far side has closed.
  *
  * A connection that closed while it sat idle is discarded by zio-http before it is handed out, so it costs nothing. A connection the far
  * side closes at the moment the request is written to it cannot be seen beforehand: that request fails once, and the shared client
  * sends the safe ones again.
  */
object StaleConnectionSpec extends ZIOSpecDefault:

  /** An HTTP/1.1 keep-alive server on a raw socket, so the spec decides exactly when a connection ends.
    *
    * @param idleClose the far side's idle timeout: a connection that sends nothing for this long is closed
    * @param dropRequest a request (counted from 1, over all connections) the server reads and then closes the connection
    *                    on without answering: the request that meets a connection the far side closed at that moment
    */
  private final class Peer(idleClose: Option[Duration], dropRequest: Set[Int]):
    val connections = AtomicInteger(0)
    val requests = AtomicInteger(0)
    private val socket = ServerSocket(0)
    val port: Int = socket.getLocalPort

    private val acceptor = new Thread(() =>
      try
        while true do
          val client = socket.accept()
          connections.incrementAndGet()
          val handler = new Thread(() => serve(client))
          handler.setDaemon(true)
          handler.start()
      catch case _: Throwable => (),
    )
    acceptor.setDaemon(true)
    acceptor.start()

    private def serve(client: Socket): Unit =
      try
        idleClose.foreach(idle => client.setSoTimeout(idle.toMillis.toInt))
        val in = client.getInputStream
        val out = client.getOutputStream
        val buffer = StringBuilder()
        var open = true
        while open do
          val byte = in.read()
          if byte < 0 then open = false
          else
            buffer.append(byte.toChar)
            if buffer.endsWith("\r\n\r\n") then
              buffer.clear()
              val n = requests.incrementAndGet()
              if dropRequest.contains(n) then open = false
              else
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok".getBytes("UTF-8"))
                out.flush()
      catch case _: SocketTimeoutException => ()
      finally client.close()

    def close(): Unit = socket.close()

  private def peer(idleClose: Option[Duration] = None, dropRequest: Set[Int] = Set.empty): ZIO[Scope, Nothing, Peer] =
    ZIO.acquireRelease(ZIO.succeed(Peer(idleClose, dropRequest)))(p => ZIO.succeed(p.close()))

  private def get(client: Client, port: Int): UIO[Either[Throwable, String]] =
    ZIO.scoped(client.request(Request.get(URL.decode(s"http://localhost:$port/").toOption.get)).flatMap(_.body.asString)).either

  private def clientFor(config: ZClient.Config): ZIO[Scope, Throwable, Client] =
    (ZLayer.succeed(config) ++ ZLayer.succeed(zio.http.netty.NettyConfig.defaultWithFastShutdown) ++ DnsResolver.default >>> ZClient.live)
      .build.map(_.get[Client])

  private val tracing: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  /** The client as the services use it: the raw one with the shared middleware. */
  private def sharedClientFor(config: ZClient.Config): ZIO[Scope, Throwable, Client] =
    for
      raw <- clientFor(config)
      traced <- tracing.build.map(_.get[Tracing])
    yield raw @@ Observability.clientMiddleware(traced)

  /** The pool the services ship with: `Dynamic(0, 128, 30s)`. */
  private val shipped = Observability.clientConfig(Observability.poolConfig(None, None, None, None))

  def spec = suite("a pooled connection the far side closed while it was idle (#579)")(
    test("closed by the far side's idle timeout: the next request opens a new connection and succeeds") {
      ZIO.scoped:
        for
          server <- peer(idleClose = Some(500.millis))
          client <- clientFor(shipped)
          first <- get(client, server.port)
          _ <- ZIO.sleep(2.seconds)
          second <- get(client, server.port)
        yield assertTrue(first == Right("ok"), second == Right("ok"), server.connections.get == 2)
    },
    test("closed by the client's own idle timeout: the next request opens a new connection and succeeds") {
      ZIO.scoped:
        for
          server <- peer()
          client <- clientFor(shipped.copy(idleTimeout = Some(500.millis)))
          first <- get(client, server.port)
          _ <- ZIO.sleep(2.seconds)
          second <- get(client, server.port)
        yield assertTrue(first == Right("ok"), second == Right("ok"), server.connections.get == 2)
    },
    test("closed by the far side at the moment a GET is sent: the shared client sends it again and the caller sees no failure") {
      ZIO.scoped:
        for
          server <- peer(dropRequest = Set(2))
          client <- sharedClientFor(shipped)
          first <- get(client, server.port)
          second <- get(client, server.port)
        yield assertTrue(first == Right("ok"), second == Right("ok"), server.requests.get == 3, server.connections.get == 2)
    },
    test("the same without the shared client's middleware fails that one request, which is what the repeat is for") {
      ZIO.scoped:
        for
          server <- peer(dropRequest = Set(2))
          client <- clientFor(shipped)
          first <- get(client, server.port)
          second <- get(client, server.port)
          third <- get(client, server.port)
        yield assertTrue(first == Right("ok"), second.isLeft, third == Right("ok"))
    },
    test("a request that changes something is not sent again: the caller is told, since the far side may have acted on it") {
      ZIO.scoped:
        for
          server <- peer(dropRequest = Set(2))
          client <- sharedClientFor(shipped)
          first <- get(client, server.port)
          second <- ZIO.scoped(client.request(Request.post(URL.decode(s"http://localhost:${server.port}/").toOption.get, Body.fromString("x"))))
            .flatMap(_.body.asString).either
        yield assertTrue(first == Right("ok"), second.isLeft, server.requests.get == 2)
    },
    test("a connection that is closed every time is tried twice and then reported, not retried without end") {
      ZIO.scoped:
        for
          server <- peer(dropRequest = Set(1, 2, 3, 4))
          client <- sharedClientFor(shipped)
          result <- get(client, server.port)
        yield assertTrue(result.isLeft, server.requests.get == 2)
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
