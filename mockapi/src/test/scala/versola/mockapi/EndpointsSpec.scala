package versola.mockapi

import zio.*
import zio.http.*
import zio.metrics.Metric
import zio.test.*

/** Runs against a sampler whose table is a single fixed 1 ms entry, so the routes are asserted
  * on without waiting for real delays and without any dependence on which branch was drawn.
  * The mixture itself is [[DelaySamplerSpec]]'s subject.
  */
object EndpointsSpec extends ZIOSpecDefault:

  private val samplers: DelayProfile => DelaySampler = _ => DelaySampler.make(MixtureWeights.read)

  private val routes = Endpoints.routes(samplers)

  private def call(method: Method, path: String): ZIO[Scope, Nothing, Response] =
    routes.runZIO(Request(method = method, url = URL.decode(path).toOption.get))

  // The fourth element is the top-level JSON key each endpoint's fixed body is keyed on --
  // see the literals in Endpoints.scala. Asserted below alongside full JSON well-formedness, so
  // a body that regresses to a different (but still brace-balanced) shape fails here.
  private val theTen: List[(String, Method, String, String)] =
    List(
      ("accounts", Method.GET, "/resources/core/accounts", "accounts"),
      ("transactions", Method.GET, "/resources/core/accounts/acc-1/transactions?page=2", "transactions"),
      ("cards", Method.GET, "/resources/core/cards", "cards"),
      ("profile", Method.GET, "/resources/core/profile", "profile"),
      ("notifications", Method.GET, "/resources/notify/notifications", "notifications"),
      ("payment_templates", Method.GET, "/resources/pay/templates", "templates"),
      ("p2p_payment", Method.POST, "/resources/pay/p2p", "paymentId"),
      ("utility_payment", Method.POST, "/resources/pay/utility", "paymentId"),
      ("card_limits", Method.PUT, "/resources/core/cards/card-1/limits", "cardId"),
      ("device_revocation", Method.DELETE, "/resources/core/profile/security/devices/dev-1", "deviceId"),
    )

  /** A real (if minimal) JSON grammar check -- full recursive descent over objects, arrays,
    * strings (with escapes), numbers and the three literals -- rather than the brace-counting
    * `startsWith`/`endsWith` this replaces. No JSON library is on `mockapi`'s classpath by
    * design (see build.sbt's comment on the `mockapi` project), so this is self-contained;
    * scope is deliberately "is this well-formed JSON", not a full schema decoder.
    */
  private def isValidJson(input: String): Boolean =
    var i = 0
    val n = input.length

    def skipWs(): Unit =
      while i < n && input(i).isWhitespace do i += 1

    def parseLiteral(lit: String): Boolean =
      val matches = i + lit.length <= n && input.substring(i, i + lit.length) == lit
      if matches then i += lit.length
      matches

    def isHexDigit(c: Char): Boolean =
      c.isDigit || ('a' to 'f').contains(c.toLower)

    def parseString(): Boolean =
      if i >= n || input(i) != '"' then false
      else
        i += 1
        var ok = true
        var closed = false
        while ok && !closed && i < n do
          input(i) match
            case '"' => i += 1; closed = true
            case '\\' =>
              i += 1
              if i >= n then ok = false
              else
                input(i) match
                  case '"' | '\\' | '/' | 'b' | 'f' | 'n' | 'r' | 't' => i += 1
                  case 'u' =>
                    val hex = input.slice(i + 1, i + 5)
                    if hex.length == 4 && hex.forall(isHexDigit) then i += 5 else ok = false
                  case _ => ok = false
            case _ => i += 1
        ok && closed

    def parseNumber(): Boolean =
      val start = i
      if i < n && input(i) == '-' then i += 1
      if i >= n || !input(i).isDigit then false
      else
        if input(i) == '0' then i += 1 else while i < n && input(i).isDigit do i += 1
        var ok = true
        if ok && i < n && input(i) == '.' then
          i += 1
          ok = i < n && input(i).isDigit
          while ok && i < n && input(i).isDigit do i += 1
        if ok && i < n && (input(i) == 'e' || input(i) == 'E') then
          i += 1
          if i < n && (input(i) == '+' || input(i) == '-') then i += 1
          ok = i < n && input(i).isDigit
          while ok && i < n && input(i).isDigit do i += 1
        ok && i > start

    def parseArray(): Boolean =
      i += 1
      skipWs()
      var closed = i < n && input(i) == ']'
      if closed then i += 1
      var ok = true
      while ok && !closed do
        ok = parseValue()
        if ok then
          skipWs()
          if i < n && input(i) == ',' then
            i += 1
            skipWs()
          else if i < n && input(i) == ']' then
            i += 1
            closed = true
          else
            ok = false
      ok && closed

    def parseObject(): Boolean =
      i += 1
      skipWs()
      var closed = i < n && input(i) == '}'
      if closed then i += 1
      var ok = true
      while ok && !closed do
        skipWs()
        ok = i < n && input(i) == '"' && parseString()
        if ok then
          skipWs()
          ok = i < n && input(i) == ':'
          if ok then
            i += 1
            ok = parseValue()
        if ok then
          skipWs()
          if i < n && input(i) == ',' then
            i += 1
          else if i < n && input(i) == '}' then
            i += 1
            closed = true
          else
            ok = false
      ok && closed

    def parseValue(): Boolean =
      skipWs()
      if i >= n then false
      else
        input(i) match
          case '{'          => parseObject()
          case '['          => parseArray()
          case '"'          => parseString()
          case 't'          => parseLiteral("true")
          case 'f'          => parseLiteral("false")
          case 'n'          => parseLiteral("null")
          case c if c == '-' || c.isDigit => parseNumber()
          case _            => false

    val ok = parseValue()
    skipWs()
    ok && i == n

  private def counterValue(endpoint: String): UIO[Double] =
    Metric.counter("mockapi_requests_total").tagged("endpoint", endpoint).value.map(_.count)

  private def histogramState(profile: String): UIO[zio.metrics.MetricState.Histogram] =
    Metric
      .histogram("mockapi_delay_seconds", Endpoints.delaySecondsBoundaries)
      .tagged("profile", profile)
      .value

  def spec = suite("Endpoints")(
    test("every one of the ten actions answers 200 with a well-formed JSON body carrying its own field") {
      ZIO
        .foreach(theTen): (name, method, path, expectedField) =>
          for
            response <- call(method, path)
            body <- response.body.asString
          yield assertTrue(
            response.status == Status.Ok,
            response.header(Header.ContentType).map(_.mediaType) == Some(MediaType.application.json),
            isValidJson(body),
            body.contains("\"" + expectedField + "\":"),
          ).label(name)
        .map(_.reduce(_ && _))
    },
    test("each action has its own body") {
      for
        bodies <- ZIO.foreach(theTen)((_, method, path, _) => call(method, path).flatMap(_.body.asString))
      yield assertTrue(bodies.distinct.size == theTen.size)
    },
    test("the transactions route works without the page query parameter") {
      for response <- call(Method.GET, "/resources/core/accounts/acc-1/transactions")
      yield assertTrue(response.status == Status.Ok)
    },
    test("counts every served request under its own endpoint label") {
      for
        accountsBefore <- counterValue("accounts")
        p2pBefore <- counterValue("p2p_payment")
        _ <- call(Method.GET, "/resources/core/accounts").repeatN(2)
        accountsAfter <- counterValue("accounts")
        p2pAfter <- counterValue("p2p_payment")
      yield assertTrue(accountsAfter - accountsBefore == 3.0, p2pAfter == p2pBefore)
    },
    test("records the sampled delay in the histogram of the endpoint's profile") {
      for
        before <- histogramState("write")
        _ <- call(Method.POST, "/resources/pay/p2p")
        after <- histogramState("write")
      yield assertTrue(after.count - before.count == 1L, after.sum > before.sum)
    },
    test("an unmapped path is not served") {
      for response <- call(Method.GET, "/resources/core/loans")
      yield assertTrue(response.status == Status.NotFound)
    },
    test("a mapped path with the wrong method is not served") {
      for response <- call(Method.POST, "/resources/core/accounts")
      yield assertTrue(response.status == Status.NotFound)
    },
    test("isValidJson accepts a real fixture shape and rejects brace-balanced garbage") {
      val wellFormed = """{"a":[1,2.5e1,-3,true,false,null,"x"],"b":{}}"""
      assertTrue(
        isValidJson(wellFormed),
        !isValidJson("{not json}"),
        !isValidJson("""{"a":}"""),
        !isValidJson("""{"a":1,}"""),
        !isValidJson("""{"a":1}trailing"""),
        !isValidJson("""{"a":"unterminated}"""),
      )
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
