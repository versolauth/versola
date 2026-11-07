package versola.edge

import zio.*
import zio.http.*
import zio.test.*

import java.io.{BufferedReader, InputStreamReader}
import java.net.{ServerSocket, Socket, URLDecoder}
import java.nio.charset.StandardCharsets

/** The path edge matches is the path the resource receives: a real zio-http server (the one edge's routes run on) reads a raw request
  * target, [[ProxyPath]] reads the segments the route hands over, and a real client writes the same path to a socket that records the
  * request line.
  */
object ProxyPathWireSpec extends ZIOSpecDefault:

  private val prefix = "/api/"

  /** An upstream that answers every request and keeps its request lines. */
  private def recordingUpstream: ZIO[Scope, Throwable, (Int, Ref[Vector[String]])] =
    for
      socket <- ZIO.acquireRelease(ZIO.attempt(ServerSocket(0)))(socket => ZIO.attempt(socket.close()).ignore)
      lines <- Ref.make(Vector.empty[String])
      _ <- ZIO.attemptBlocking:
        val connection = socket.accept()
        val requestLine = BufferedReader(InputStreamReader(connection.getInputStream, StandardCharsets.ISO_8859_1)).readLine()
        Unsafe.unsafe(unsafe ?=> Runtime.default.unsafe.run(lines.update(_ :+ requestLine)).getOrThrow())
        connection.getOutputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1))
        connection.close()
      .forever.ignore.forkDaemon
    yield (socket.getLocalPort, lines)

  private def send(port: Int, target: String): Task[Unit] =
    ZIO.attemptBlocking:
      val socket = Socket("localhost", port)
      socket.getOutputStream.write(s"GET $target HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1))
      socket.getOutputStream.flush()
      BufferedReader(InputStreamReader(socket.getInputStream, StandardCharsets.ISO_8859_1)).readLine()
      socket.close()

  /** What edge matched (`None`: refused, nothing sent) and the path the resource was sent, for one raw request target. */
  private def through(target: String): ZIO[Client & Server, Throwable, (Option[Vector[String]], Option[String])] =
    ZIO.scoped:
      for
        (upstreamPort, lines) <- recordingUpstream
        client <- ZIO.service[Client]
        matched <- Ref.make(Option.empty[Vector[String]])
        backend = URL.decode(s"http://localhost:$upstreamPort/api").toOption.get
        routes = Routes(Method.GET / "resources" / string("id") / trailing -> handler { (_: String, rest: Path, _: Request) =>
          val segments = ProxyPath.segments(rest)
          matched.set(segments) *>
            ZIO.foreachDiscard(segments)(_ => ZIO.scoped(client.request(Request.get(backend.addPath(rest)))).ignore)
              .as(Response.ok)
        })
        edgePort <- Server.install(routes)
        _ <- send(edgePort, "/resources/users-api" + target)
        // the upstream is called inside the handler, before the answer to `send`
        sent <- lines.get.map(_.headOption)
        segments <- matched.get
      yield (segments, sent.flatMap(_.split(' ').lift(1)))

  private def decoded(sent: String): Vector[String] =
    sent.stripPrefix(prefix).split('/').toVector.filter(_.nonEmpty).map(URLDecoder.decode(_, StandardCharsets.UTF_8))

  private val refused = List(
    "/users/..;/x",
    "/users/%252e%252e",
    "/users/a%2Fb",
    "/users/a%2fb",
    "/users/a%5Cb",
    "/users/a\\b",
    "/users/a%00b",
  )

  private val forwarded = List(
    "/users",
    "/users/user-1",
    "/users/a%20b",
    "/users/a/../b",
    "/users/a/%2e%2e/b",
    "/users/./a",
    "/users/a//b",
    "/users/a?x=1",
    // zio-http collapses a plain dot segment before the route: what is matched is then the collapsed path, and it is what is sent
    "/users/..",
    "/users/%2e%2e",
    "/users/%2E%2E",
  )

  private val forwardedTests = forwarded.map: target =>
    test(target) {
      through(target).map: (matched, sent) =>
        assertTrue(matched.isDefined, matched == sent.map(decoded))
    }

  private val refusedTests = refused.map: target =>
    test(target) {
      through(target).map: (matched, sent) =>
        assertTrue(matched.isEmpty, sent.isEmpty)
    }

  def spec = suite("ProxyPath on the wire")(
    suite("a path edge matched is the path the resource received")(forwardedTests*),
    suite("a path the two could read differently is refused, and nothing is sent")(refusedTests*),
  ).provide(Client.default, Server.live, ZLayer.succeed(Server.Config.default.onAnyOpenPort)) @@ TestAspect.withLiveClock @@ TestAspect.sequential
