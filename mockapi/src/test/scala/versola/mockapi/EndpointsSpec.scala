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

  private val theTen: List[(String, Method, String)] =
    List(
      ("accounts", Method.GET, "/resources/core/accounts"),
      ("transactions", Method.GET, "/resources/core/accounts/acc-1/transactions?page=2"),
      ("cards", Method.GET, "/resources/core/cards"),
      ("profile", Method.GET, "/resources/core/profile"),
      ("notifications", Method.GET, "/resources/notify/notifications"),
      ("payment_templates", Method.GET, "/resources/pay/templates"),
      ("p2p_payment", Method.POST, "/resources/pay/p2p"),
      ("utility_payment", Method.POST, "/resources/pay/utility"),
      ("card_limits", Method.PUT, "/resources/core/cards/card-1/limits"),
      ("device_revocation", Method.DELETE, "/resources/core/profile/security/devices/dev-1"),
    )

  private def counterValue(endpoint: String): UIO[Double] =
    Metric.counter("mockapi_requests_total").tagged("endpoint", endpoint).value.map(_.count)

  private def histogramState(profile: String): UIO[zio.metrics.MetricState.Histogram] =
    Metric
      .histogram("mockapi_delay_seconds", Endpoints.delaySecondsBoundaries)
      .tagged("profile", profile)
      .value

  def spec = suite("Endpoints")(
    test("every one of the ten actions answers 200 with a JSON body") {
      ZIO
        .foreach(theTen): (name, method, path) =>
          for
            response <- call(method, path)
            body <- response.body.asString
          yield assertTrue(
            response.status == Status.Ok,
            response.header(Header.ContentType).map(_.mediaType) == Some(MediaType.application.json),
            body.startsWith("{"),
            body.endsWith("}"),
          ).label(name)
        .map(_.reduce(_ && _))
    },
    test("each action has its own body") {
      for
        bodies <- ZIO.foreach(theTen)((_, method, path) => call(method, path).flatMap(_.body.asString))
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
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
