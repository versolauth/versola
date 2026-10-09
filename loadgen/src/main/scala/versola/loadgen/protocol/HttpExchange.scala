package versola.loadgen.protocol

import io.netty.handler.codec.PrematureChannelClosureException
import versola.loadgen.metrics.LoadgenMetrics
import zio.http.*
import zio.{Duration, IO, NonEmptyChunk, ZIO}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException

/** What a request came back as: the response plus its body, read exactly once.
  *
  * The body is materialized here rather than by whoever needs it, so that the success and the
  * failure paths cannot disagree about whether it was consumed (§3.3). The client is a batched
  * one, so the bytes are already in memory by the time this exists -- reading them is a String
  * allocation, not another round trip.
  */
private[protocol] case class Received(response: Response, body: String):
  def status: Status = response.status

  def location: Option[String] = response.header(Header.Location).map(_.url.encode)

/** The one HTTP call site every protocol client goes through: applies the request timeout,
  * maps every way the transport can fail into [[ProtocolError.Transport]], and reads the body.
  *
  * `client.batched` is taken once here, not per call. The e2e original wrapped every request in
  * `Client.batched(req).provide(ZLayer.succeed(client))`, which builds a `ZLayer` per request
  * and defeats a shared connection pool (§3.2) -- the pool is the whole reason an idle virtual
  * user costs no socket (design doc §6.3).
  */
private[protocol] final class HttpExchange(client: Client, requestTimeout: Duration):
  private val http = client.batched

  def send(request: Request): IO[ProtocolError, Received] =
    InflightRequests.around:
      attempt(request).catchSome:
        // A pooled connection the far side closed while the request was going out (a proxy that
        // retires a connection after N requests, or an idle timeout racing the next request):
        // the request was never answered, and for a method that is safe to repeat it is retried
        // once on a fresh connection, as a browser does (RFC 9112 §9.3.1). Counted, so a rate
        // that climbs is visible rather than absorbed.
        case ProtocolError.Transport(cause) if HttpExchange.retriable(request.method, cause) =>
          LoadgenMetrics.transportRetried *> attempt(request)

  private def attempt(request: Request): IO[ProtocolError, Received] =
    http
      .request(request)
      .timeoutFail(HttpExchange.timedOut)(requestTimeout)
      .flatMap(response => response.body.asString.map(Received(response, _)))
      .mapError(ProtocolError.Transport.apply)

private[protocol] object HttpExchange:
  /** One shared instance: filling in a stack trace per timed-out request is exactly the kind of
    * cost that shows up as the emulator's own latency once the SUT starts timing out.
    */
  private val timedOut = TimeoutException("loadgen request timeout")

  /** Whether a failed request may be sent again unchanged: the connection closed under it before
    * any response, and its method is safe (RFC 9110 §9.2.1) so repeating it cannot do twice what
    * it did once. A POST is never retried here -- the caller knows whether its form is replayable,
    * and `/token` and `/par` are not.
    */
  def retriable(method: Method, cause: Throwable): Boolean =
    (method == Method.GET || method == Method.HEAD) && cause.isInstanceOf[PrematureChannelClosureException]

  val formContentType: Header.ContentType = Header.ContentType(MediaType.application.`x-www-form-urlencoded`)

  def formBody(fields: List[(String, String)]): Body =
    val encoded = StringBuilder()
    fields.foreach: (name, value) =>
      if encoded.nonEmpty then encoded.append('&')
      encoded.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
      encoded.append('=')
      encoded.append(URLEncoder.encode(value, StandardCharsets.UTF_8))
    Body.fromString(encoded.result())

  def cookieHeader(name: String, value: String): Header.Cookie =
    Header.Cookie(NonEmptyChunk(Cookie.Request(name, value)))

  /** The whole `Set-Cookie` a response carries under `name`, not just its value: `EDGE_SESSION`
    * arrives with the `Max-Age` that is the web session's expiry (see [[EdgeCookie]]), and the
    * header is parsed either way, so returning the value alone would throw away the one field
    * the caller cannot reconstruct.
    */
  def setCookie(response: Response, name: String): Option[Cookie.Response] =
    response.headers.getAll(Header.SetCookie).collectFirst { case header if header.value.name == name => header.value }

  /** Query parameters on the redirect the SUT answered with. Decoding the whole URL costs one
    * parse, but a redirect happens once per conversation, not once per hop.
    */
  def redirectParam(location: String, name: String): Option[String] =
    URL.decode(location).toOption.flatMap(_.queryParams.getAll(name).headOption)

  def isRedirect(status: Status): Boolean =
    status.code >= 300 && status.code < 400

  def unexpected(expected: Set[Status], received: Status, endpoint: String): ProtocolError =
    ProtocolError.UnexpectedStatus(expected, received, endpoint)

  /** Lifts an `Option` into the error channel without building the failure message unless it is
    * actually taken -- the e2e original's assertion extensions interpolate on every call, on
    * paths that succeed 99.9% of the time (§3.2).
    */
  def required[A](value: Option[A], endpoint: String, detail: => String): IO[ProtocolError, A] =
    value match
      case Some(present) => ZIO.succeed(present)
      case None => ZIO.fail(ProtocolError.MalformedResponse(endpoint, detail))
