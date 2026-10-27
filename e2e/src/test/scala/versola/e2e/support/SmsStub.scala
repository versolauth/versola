package versola.e2e.support

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import zio.*

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** An SMS provider for a test to send to: it answers `200` to every request and keeps what it was sent, so a test can
  * read the method, the credentials and the JSON body auth's HTTP client built. Loopback only, like [[SmtpSink]]. */
final class SmsStub private (server: HttpServer):
  import SmsStub.*

  private val received = ConcurrentLinkedQueue[Received]()

  val port: Int = server.getAddress.getPort

  def requests: UIO[List[Received]] = ZIO.succeed(received.asScala.toList)

  private def stop(): Unit = server.stop(0)

  /** Waits until at least `count` requests have arrived, or fails after `within`. */
  def awaitRequests(count: Int, within: Duration = 15.seconds): Task[List[Received]] =
    requests
      .repeat(Schedule.spaced(100.millis) *> Schedule.recurUntil[List[Received]](_.length >= count))
      .timeoutFail(RuntimeException(s"the SMS stub received fewer than $count request(s) within $within"))(within)

  server.createContext("/", (exchange: HttpExchange) =>
    val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
    received.add(
      Received(
        method = exchange.getRequestMethod,
        path = exchange.getRequestURI.getPath,
        authorization = Option(exchange.getRequestHeaders.getFirst("Authorization")),
        contentType = Option(exchange.getRequestHeaders.getFirst("Content-Type")),
        body = body,
      ),
    )
    exchange.sendResponseHeaders(200, -1)
    exchange.close(),
  )
  server.start()

object SmsStub:
  case class Received(method: String, path: String, authorization: Option[String], contentType: Option[String], body: String)

  /** A stub on a free loopback port, stopped when the scope ends. */
  val scoped: ZIO[Scope, Nothing, SmsStub] =
    ZIO.acquireRelease(
      ZIO.succeed(SmsStub(HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0))),
    )(stub => ZIO.succeed(stub.stop()))
