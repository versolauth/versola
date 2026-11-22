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

  /** The client as the services use it: the raw one, the shared middleware and the guard, in that order, with the guard in `mode` and the
    * peer's retry budget as given. `exempt`: `None` leaves the guard unarmed (the time before the service is up), `Some(peers)` arms it with those
    * peers exempt. */
  private def sharedClientFor(
      config: ZClient.Config,
      mode: GuardMode = GuardMode.Shadow,
      budget: RetryBudgetSettings = RetryBudgetSettings.default,
      exempt: Option[Set[String]] = Some(Set.empty),
  ): ZIO[Scope, Throwable, Client] =
    for
      raw <- clientFor(config)
      traced <- tracing.build.map(_.get[Tracing])
      guard <- OutboundGuard.make(OutboundGuard.initialSettings(Observability.defaultClientPoolSize).copy(mode = mode, retryBudget = budget))
      _ <- ZIO.foreachDiscard(exempt)(peers => guard.arm(peers))
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
    suite("the repeat and the retry budget")(
      test("in enforce, a peer whose retry budget is spent is not sent the GET again: the caller is told, and so is the log") {
        ZIO.scoped:
          for
            server <- peer(drop = _ == 2)
            client <- sharedClientFor(shipped, mode = GuardMode.Enforce, budget = RetryBudgetSettings(ratio = 0, minPerSecond = 0))
            first <- get(client, server.port)
            second <- get(client, server.port)
            logs <- ZTestLogger.logOutput
          yield assertTrue(
            first == Right("ok"),
            second.isLeft,
            server.requests.get == 2,
            logs.exists(line => line.logLevel == LogLevel.Error && line.message().contains("not sent again")),
          )
      },
      test("in enforce, a peer whose retry budget has tokens is sent the GET again, and the caller sees no failure") {
        ZIO.scoped:
          for
            server <- peer(drop = _ == 2)
            client <- sharedClientFor(shipped, mode = GuardMode.Enforce)
            first <- get(client, server.port)
            // the budget refills at five tokens a second from the first call, so 300 ms leaves one and a half
            _ <- ZIO.sleep(300.millis)
            second <- get(client, server.port)
          yield assertTrue(first == Right("ok"), second == Right("ok"), server.requests.get == 3)
      },
      test("in shadow, the default, the repeat is made whatever the budget: a denial is only counted") {
        ZIO.scoped:
          for
            server <- peer(drop = _ == 2)
            client <- sharedClientFor(shipped, mode = GuardMode.Shadow, budget = RetryBudgetSettings(ratio = 0, minPerSecond = 0))
            first <- get(client, server.port)
            second <- get(client, server.port)
          yield assertTrue(first == Right("ok"), second == Right("ok"), server.requests.get == 3)
      },
      test("with the guard off there is no repeat") {
        ZIO.scoped:
          for
            server <- peer(drop = _ == 2)
            client <- sharedClientFor(shipped, mode = GuardMode.Off)
            first <- get(client, server.port)
            second <- get(client, server.port)
          yield assertTrue(first == Right("ok"), second.isLeft, server.requests.get == 2)
      },
      test("an exempt peer is read as shadow in enforce, as the guard leaves it alone: the repeat is made though the budget is spent") {
        ZIO.scoped:
          for
            server <- peer(drop = _ == 2)
            client <- sharedClientFor(
              shipped,
              mode = GuardMode.Enforce,
              budget = RetryBudgetSettings(ratio = 0, minPerSecond = 0),
              exempt = Some(Set(s"localhost:${server.port}")),
            )
            first <- get(client, server.port)
            second <- get(client, server.port)
          yield assertTrue(first == Right("ok"), second == Right("ok"), server.requests.get == 3)
      },
      test("before the guard is armed, enforce is read as shadow too") {
        ZIO.scoped:
          for
            server <- peer(drop = _ == 2)
            client <- sharedClientFor(shipped, mode = GuardMode.Enforce, budget = RetryBudgetSettings(ratio = 0, minPerSecond = 0), exempt = None)
            first <- get(client, server.port)
            second <- get(client, server.port)
          yield assertTrue(first == Right("ok"), second == Right("ok"), server.requests.get == 3)
      },
    ),
    suite("what the first attempt logs")(
      test("a failure the caller never sees, because the repeat succeeded, is a warning and not an error") {
        ZIO.scoped:
          for
            server <- peer(drop = _ == 2)
            client <- sharedClientFor(shipped)
            _ <- get(client, server.port)
            // the test logger is the whole spec's: only what this case wrote
            before <- ZTestLogger.logOutput.map(_.size)
            second <- get(client, server.port)
            logs <- ZTestLogger.logOutput.map(_.drop(before))
            sendHttp = logs.filter(_.message() == "send-http")
          yield assertTrue(
            second == Right("ok"),
            sendHttp.exists(_.logLevel == LogLevel.Warning),
            !sendHttp.exists(_.logLevel == LogLevel.Error),
          )
      },
      test("the same failure of a request that is not sent again is an error") {
        ZIO.scoped:
          for
            server <- peer(drop = _ == 2)
            client <- sharedClientFor(shipped)
            _ <- get(client, server.port)
            before <- ZTestLogger.logOutput.map(_.size)
            second <- read(client, Request.post(urlOf(server.port), Body.fromString("x")))
            logs <- ZTestLogger.logOutput.map(_.drop(before))
            sendHttp = logs.filter(_.message() == "send-http")
          yield assertTrue(second.isLeft, sendHttp.exists(_.logLevel == LogLevel.Error))
      },
    ),
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
