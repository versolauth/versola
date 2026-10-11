package versola.loadgen.protocol

import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.test.*

/** `HttpExchange.send` is the one call site every protocol client goes through (its own doc
  * comment), so a gap in its timeout would be a gap for all of them at once. `requestTimeout`
  * wraps `client.batched.request(...)` and not the `response.body.asString` that follows it,
  * which would leave the body read unbounded if `batched` streamed lazily -- it does not (it
  * reads the whole response, body included, before resolving), so the timeout already covers
  * both today. This guards that property explicitly, rather than leaving it an unstated fact
  * about a dependency `HttpExchange` does not otherwise assert.
  */
object HttpExchangeSpec extends ZIOSpecDefault:

  override def aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] = Chunk(TestAspect.withLiveClock)

  private val requestTimeout = 500.millis

  /** Answers with a `200` immediately, then drips its body slowly enough to outlast
    * `requestTimeout` well before it finishes -- the shape of a SUT that answered promptly but
    * stalled serving the page, not one that never answered at all.
    */
  private val slowBodyRoute: Routes[Any, Nothing] =
    Routes(
      Method.GET / "slow" -> handler:
        Response(body = Body.fromStreamChunked(ZStream.fromIterable("partial".getBytes.toIndexedSeq).schedule(Schedule.spaced(2.seconds)))),
    )

  private val fastRoute: Routes[Any, Nothing] =
    Routes(Method.GET / "fast" -> handler(Response.text("ok")))

  def spec = suite("HttpExchange")(
    // The unanswered request on a connection the far side retired is repeated for a safe method
    // and never for a POST, whose form may not be replayable (a code, a refresh token).
    test("repeats a GET or HEAD whose connection was closed under it, and nothing else") {
      val closed = io.netty.handler.codec.PrematureChannelClosureException()
      assertTrue(
        HttpExchange.retriable(Method.GET, closed),
        HttpExchange.retriable(Method.HEAD, closed),
        !HttpExchange.retriable(Method.POST, closed),
        !HttpExchange.retriable(Method.PUT, closed),
        !HttpExchange.retriable(Method.GET, java.util.concurrent.TimeoutException("slow")),
      )
    },
    // Everything a warm-up opened goes stale together, so one repeat can meet the next stale
    // connection of the batch; the bound keeps a far side that closes for another reason visible.
    test("repeats a closed connection up to the bound, then gives up with the last failure") {
      val closed = ProtocolError.Transport(io.netty.handler.codec.PrematureChannelClosureException())
      val onlyClosed: PartialFunction[ProtocolError, Unit] = { case ProtocolError.Transport(_) => () }
      for
        calls <- Ref.make(0)
        failing = calls.updateAndGet(_ + 1).flatMap(n => ZIO.fail(closed))
        gaveUp <- HttpExchange.retrying(failing, HttpExchange.maxRetries)(onlyClosed).either
        attempts <- calls.get
        _ <- calls.set(0)
        flaky = calls.updateAndGet(_ + 1).flatMap(n => if n <= 2 then ZIO.fail(closed) else ZIO.succeed("ok"))
        recovered <- HttpExchange.retrying(flaky, HttpExchange.maxRetries)(onlyClosed).either
        _ <- calls.set(0)
        other = calls.updateAndGet(_ + 1) *> ZIO.fail(ProtocolError.Transport(RuntimeException("refused")))
        notRetried <- HttpExchange.retrying(other, HttpExchange.maxRetries) {
          case ProtocolError.Transport(c) if c.isInstanceOf[io.netty.handler.codec.PrematureChannelClosureException] => ()
        }.either
        refusedAttempts <- calls.get
      yield assertTrue(
        gaveUp == Left(closed),
        attempts == HttpExchange.maxRetries + 1,
        recovered == Right("ok"),
        notRetried.isLeft,
        refusedAttempts == 1,
      )
    },
    test("times out a request whose body stalls, not just one whose headers never arrive") {
      for
        _ <- TestClient.addRoutes(slowBodyRoute)
        client <- ZIO.service[Client]
        exchange = HttpExchange(client, requestTimeout)
        start <- Clock.instant
        result <- exchange.send(Request.get(URL.decode("http://sut.test/slow").toOption.get)).either
        elapsed <- Clock.instant.map(java.time.Duration.between(start, _))
      yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[ProtocolError.Transport]),
        // Generous relative to requestTimeout so this isn't flaky on a loaded CI box, but far
        // short of the slow route's own 2s-per-byte schedule -- the failure this proves is
        // "bounded by requestTimeout", not merely "eventually fails".
        elapsed.toMillis < 1500,
      )
    },
    test("a response whose body arrives promptly still succeeds") {
      for
        _ <- TestClient.addRoutes(fastRoute)
        client <- ZIO.service[Client]
        exchange = HttpExchange(client, requestTimeout)
        result <- exchange.send(Request.get(URL.decode("http://sut.test/fast").toOption.get))
      yield assertTrue(result.status == Status.Ok, result.body == "ok")
    },
  ).provide(TestClient.layer)
