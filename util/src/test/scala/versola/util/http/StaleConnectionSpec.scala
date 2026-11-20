package versola.util.http

import io.opentelemetry.api
import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.net.{ServerSocket, Socket, SocketTimeoutException}
import java.util.concurrent.atomic.AtomicInteger

/** #579: what the shared client does with a pooled connection the far side has closed.
  *
  * A connection that closed while it sat idle is discarded by zio-http before it is handed out, so it costs nothing. A connection the far
  * side closes while the request is being written to it cannot be seen beforehand: that request fails once, and the shared client sends the
  * safe ones again, within the peer's retry budget.
  *
  * The far side fails the request in the two ways a real one does, and the spec holds the repeat to both: the connection is closed after the
  * request was read (the client sees the FIN: `PrematureChannelClosureException`), or it is reset (the client sees the RST: `Connection reset
  * by peer`, or `Broken pipe`). Which of the two a real close gives depends on what had been sent when it happened.
  */
object StaleConnectionSpec extends ZIOSpecDefault:

  /** An HTTP/1.1 keep-alive server on a raw socket, so the spec decides exactly when a connection ends.
    *
    * @param idleClose the far side's idle timeout: a connection that sends nothing for this long is closed
    * @param drop requests (counted from 1, over all connections) the server reads and then closes the connection on without answering
    * @param reset requests it reads and then resets the connection on (`SO_LINGER` 0, which sends an RST) without answering
    * @param closeAfterResponse closes the connection as soon as it has answered, so the next request of the client meets a connection the
    *                           far side has just closed: whether the client has seen that by then is a race, which is the one of #579
    */
  private final class Peer(
      idleClose: Option[Duration],
      drop: Int => Boolean,
      reset: Int => Boolean,
      closeAfterResponse: Boolean,
  ):
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
              if reset(n) then
                client.setSoLinger(true, 0)
                open = false
              else if drop(n) then open = false
              else
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok".getBytes("UTF-8"))
                out.flush()
                if closeAfterResponse then open = false
      catch case _: SocketTimeoutException => ()
      finally client.close()

    def close(): Unit = socket.close()

  private def peer(
      idleClose: Option[Duration] = None,
      drop: Int => Boolean = _ => false,
      reset: Int => Boolean = _ => false,
      closeAfterResponse: Boolean = false,
  ): ZIO[Scope, Nothing, Peer] =
    ZIO.acquireRelease(ZIO.succeed(Peer(idleClose, drop, reset, closeAfterResponse)))(p => ZIO.succeed(p.close()))

  private def urlOf(port: Int): URL = URL.decode(s"http://localhost:$port/").toOption.get

  private def read(client: Client, request: Request): UIO[Either[Throwable, String]] =
    ZIO.scoped(client.request(request).flatMap(_.body.asString)).either

  private def get(client: Client, port: Int): UIO[Either[Throwable, String]] = read(client, Request.get(urlOf(port)))

  private def clientFor(config: ZClient.Config): ZIO[Scope, Throwable, Client] =
    (ZLayer.succeed(config) ++ ZLayer.succeed(zio.http.netty.NettyConfig.defaultWithFastShutdown) ++ DnsResolver.default >>> ZClient.live)
      .build.map(_.get[Client])

  private val tracing: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  /** The client as the services use it: the raw one, the shared middleware and the guard, in that order, with the guard in `mode`. */
  private def sharedClientFor(config: ZClient.Config, mode: GuardMode = GuardMode.Shadow): ZIO[Scope, Throwable, Client] =
    for
      raw <- clientFor(config)
      traced <- tracing.build.map(_.get[Tracing])
      guard <- OutboundGuard.make(OutboundGuard.initialSettings(Observability.defaultClientPoolSize).copy(mode = mode))
    yield raw @@ Observability.clientMiddleware(traced) @@ guard.aspect

  /** The pool the services ship with: `Dynamic(0, 128, 30s)`. */
  private val shipped = Observability.clientConfig(Observability.poolConfig(None, None, None, None))

  def spec = suite("a pooled connection the far side closed (#579)")(
    suite("while it sat idle")(
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
    ),
    suite("while a request is written to it")(
      test("closed after the request was read: a GET through the raw client fails that one request and the next succeeds") {
        ZIO.scoped:
          for
            server <- peer(drop = _ == 2)
            client <- clientFor(shipped)
            first <- get(client, server.port)
            second <- get(client, server.port)
            third <- get(client, server.port)
          yield assertTrue(first == Right("ok"), second.isLeft, third == Right("ok"))
      },
      test("closed after the request was read: the shared client sends the GET again and the caller sees no failure") {
        ZIO.scoped:
          for
            server <- peer(drop = _ == 2)
            client <- sharedClientFor(shipped)
            first <- get(client, server.port)
            second <- get(client, server.port)
          yield assertTrue(first == Right("ok"), second == Right("ok"), server.requests.get == 3, server.connections.get == 2)
      },
      test("reset (an RST, not a FIN): a GET through the raw client fails") {
        ZIO.scoped:
          for
            server <- peer(reset = _ == 2)
            client <- clientFor(shipped)
            first <- get(client, server.port)
            second <- get(client, server.port)
          yield assertTrue(first == Right("ok"), second.isLeft)
      },
      test("reset: the shared client sends the GET again and the caller sees no failure") {
        ZIO.scoped:
          for
            server <- peer(reset = _ == 2)
            client <- sharedClientFor(shipped)
            first <- get(client, server.port)
            second <- get(client, server.port)
          yield assertTrue(first == Right("ok"), second == Right("ok"), server.requests.get == 3)
      },
      test("closed by the far side right after each answer, which the client may or may not have seen: no request fails") {
        ZIO.scoped:
          for
            server <- peer(closeAfterResponse = true)
            client <- sharedClientFor(shipped)
            results <- ZIO.foreach(1 to 40)(_ => get(client, server.port))
          yield assertTrue(results.forall(_ == Right("ok")))
      },
      test("a request that changes something is not sent again: the caller is told, since the far side may have acted on it") {
        ZIO.scoped:
          for
            server <- peer(drop = _ == 2)
            client <- sharedClientFor(shipped)
            first <- get(client, server.port)
            second <- read(client, Request.post(urlOf(server.port), Body.fromString("x")))
          yield assertTrue(first == Right("ok"), second.isLeft, server.requests.get == 2)
      },
      test("a GET whose body is a stream is not sent again: the first attempt may have consumed it") {
        ZIO.scoped:
          for
            server <- peer(drop = _ == 2)
            client <- sharedClientFor(shipped)
            first <- get(client, server.port)
            streamed = Body.fromStreamChunked(ZStream.fromChunk(Chunk.fromArray("x".getBytes("UTF-8"))))
            second <- read(client, Request.get(urlOf(server.port)).withBody(streamed))
          yield assertTrue(first == Right("ok"), second.isLeft, server.requests.get == 2)
      },
      test("a connection that is closed every time is tried twice and then reported, not repeated without end") {
        ZIO.scoped:
          for
            server <- peer(drop = _ <= 4)
            client <- sharedClientFor(shipped)
            result <- get(client, server.port)
          yield assertTrue(result.isLeft, server.requests.get == 2)
      },
    ),
    test("the repeat spends the peer's retry budget: when it is spent the failure is returned, as for any other retry") {
      ZIO.scoped:
        for
          // every other request of the server is closed on, so each GET needs its repeat; the budget holds ten and refills at five a second
          server <- peer(drop = _ % 2 == 1)
          client <- sharedClientFor(shipped, mode = GuardMode.Enforce)
          _ <- ZIO.sleep(1.second)
          results <- ZIO.foreach(1 to 40)(_ => get(client, server.port))
        yield assertTrue(results.exists(_ == Right("ok")), results.exists(_.isLeft))
    },
    test("which failures count as a closed connection: those that say so, through their causes, and not a timeout or a refusal") {
      import io.netty.handler.codec.PrematureChannelClosureException
      import java.io.IOException
      import java.net.{ConnectException, SocketTimeoutException as Timeout}
      assertTrue(
        OutboundGuard.isClosedConnection(PrematureChannelClosureException("closed")),
        OutboundGuard.isClosedConnection(java.nio.channels.ClosedChannelException()),
        OutboundGuard.isClosedConnection(IOException("recvAddress(..) failed with error(-104): Connection reset by peer")),
        OutboundGuard.isClosedConnection(IOException("writeAddress(..) failed: Broken pipe")),
        OutboundGuard.isClosedConnection(IOException("read error: -54 (connection reset by peer)")),
        OutboundGuard.isClosedConnection(RuntimeException("the call failed", IOException("Connection reset by peer"))),
        !OutboundGuard.isClosedConnection(Timeout("Read timed out")),
        !OutboundGuard.isClosedConnection(ConnectException("Connection refused")),
        !OutboundGuard.isClosedConnection(IOException("server stopped sending data")),
        !OutboundGuard.isClosedConnection(RuntimeException("boom")),
        !OutboundGuard.isClosedConnection("not a throwable"),
      )
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
