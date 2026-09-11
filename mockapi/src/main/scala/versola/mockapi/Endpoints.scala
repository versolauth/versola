package versola.mockapi

import zio.*
import zio.http.*
import zio.metrics.Metric
import zio.metrics.MetricKeyType.Histogram.Boundaries

import java.nio.charset.StandardCharsets

/** The ten protected actions of design doc §3, served behind edge.
  *
  * The paths are the doc's client-facing paths verbatim, `/resources/{resourceId}` prefix
  * included: edge appends the part of the path after `/resources/{resourceId}` to the resource's
  * configured upstream URL, so central's `core`/`pay`/`notify` resources must point at
  * `http://mockapi:8100/resources/core` and so on for the two to line up.
  */
object Endpoints:

  /** One counter per endpoint plus one delay histogram per profile. Endpoint names are
    * constants, never a request path, so the label cardinality is fixed at ten regardless of
    * how many distinct account or card ids the campaign drives through here.
    */
  private val requests = Metric.counter("mockapi_requests_total")

  /** The sampled delay, in seconds. Buckets are tight around the configured 1--50 ms range:
    * the point of this histogram is to prove the backend behaved as configured, which the
    * default wide RED buckets could not do.
    */
  val delaySecondsBoundaries: Boundaries =
    Boundaries.fromChunk(
      Chunk(0.001, 0.002, 0.003, 0.004, 0.006, 0.008, 0.010, 0.015, 0.020, 0.025, 0.030, 0.035,
        0.040, 0.045, 0.050),
    )

  private val delaySeconds = Metric.histogram("mockapi_delay_seconds", delaySecondsBoundaries)

  private final case class Endpoint(name: String, profile: DelayProfile, json: String)

  private val accounts = Endpoint(
    "accounts",
    DelayProfile.Read,
    """{"accounts":[{"id":"acc-1","currency":"EUR","balance":"1042.55"},{"id":"acc-2","currency":"EUR","balance":"18.30"}]}""",
  )

  private val transactions = Endpoint(
    "transactions",
    DelayProfile.Read,
    """{"page":0,"transactions":[{"id":"txn-1","amount":"-12.40","merchant":"kiosk"},{"id":"txn-2","amount":"-3.10","merchant":"metro"}]}""",
  )

  private val cards = Endpoint(
    "cards",
    DelayProfile.Read,
    """{"cards":[{"id":"card-1","last4":"4242","status":"active"}]}""",
  )

  private val profile = Endpoint(
    "profile",
    DelayProfile.Read,
    """{"profile":{"id":"usr-1","displayName":"load test","locale":"en-GB"}}""",
  )

  private val notifications = Endpoint(
    "notifications",
    DelayProfile.Read,
    """{"notifications":[{"id":"ntf-1","kind":"payment","read":false}]}""",
  )

  private val templates = Endpoint(
    "payment_templates",
    DelayProfile.Read,
    """{"templates":[{"id":"tpl-1","label":"rent","amount":"650.00"}]}""",
  )

  private val p2pPayment = Endpoint(
    "p2p_payment",
    DelayProfile.Write,
    """{"paymentId":"pay-p2p-1","status":"accepted"}""",
  )

  private val utilityPayment = Endpoint(
    "utility_payment",
    DelayProfile.Write,
    """{"paymentId":"pay-utility-1","status":"accepted"}""",
  )

  private val cardLimits = Endpoint(
    "card_limits",
    DelayProfile.Write,
    """{"cardId":"card-1","status":"updated"}""",
  )

  private val deviceRevocation = Endpoint(
    "device_revocation",
    DelayProfile.Write,
    """{"deviceId":"dev-1","status":"deleted"}""",
  )

  /** Everything a request needs is fixed at startup: the `Response` (bodies are pre-serialised
    * `Chunk[Byte]`, so nothing is encoded per request), the counter effect, and the sampler.
    */
  private final class Prepared(
      val sampler: DelaySampler,
      val response: Response,
      val count: UIO[Unit],
      val recordDelay: Double => UIO[Unit],
  )

  private def prepare(endpoint: Endpoint, samplers: DelayProfile => DelaySampler): Prepared =
    val bytes = Chunk.fromArray(endpoint.json.getBytes(StandardCharsets.UTF_8))
    val profileLabel = endpoint.profile match
      case DelayProfile.Read => "read"
      case DelayProfile.Write => "write"
    val histogram = delaySeconds.tagged("profile", profileLabel)
    new Prepared(
      sampler = samplers(endpoint.profile),
      response = Response(
        status = Status.Ok,
        headers = Headers(Header.ContentType(MediaType.application.json)),
        body = Body.fromChunk(bytes),
      ),
      count = requests.tagged("endpoint", endpoint.name).increment,
      recordDelay = seconds => histogram.update(seconds),
    )

  /** One index draw feeds both the sleep and the histogram, so the delay the report reads is
    * exactly the delay the caller waited. `ZIO.sleep` and never a blocking sleep: at ~8,400 rps
    * and a ~10 ms mean this parks ~90 fibers, whereas a blocked thread each would need a pool
    * the size of the concurrency.
    */
  private def respond(prepared: Prepared): UIO[Response] =
    ZIO.suspendSucceed:
      val index = prepared.sampler.drawIndex()
      ZIO.sleep(prepared.sampler.durationAt(index)) *>
        prepared.count *>
        prepared.recordDelay(prepared.sampler.microsAt(index) / 1000000.0) *>
        ZIO.succeed(prepared.response)

  def routes(samplers: DelayProfile => DelaySampler): Routes[Any, Nothing] =
    val accountsPrepared = prepare(accounts, samplers)
    val transactionsPrepared = prepare(transactions, samplers)
    val cardsPrepared = prepare(cards, samplers)
    val profilePrepared = prepare(profile, samplers)
    val notificationsPrepared = prepare(notifications, samplers)
    val templatesPrepared = prepare(templates, samplers)
    val p2pPrepared = prepare(p2pPayment, samplers)
    val utilityPrepared = prepare(utilityPayment, samplers)
    val cardLimitsPrepared = prepare(cardLimits, samplers)
    val deviceRevocationPrepared = prepare(deviceRevocation, samplers)

    Routes(
      Method.GET / "resources" / "core" / "accounts" -> handler { (_: Request) =>
        respond(accountsPrepared)
      },
      // `?page=` is accepted and ignored: the response is a fixed body by design, and matching
      // on the query would only give the campaign a way to get a 404 out of a backend whose
      // contract is "always 200 after a delay".
      Method.GET / "resources" / "core" / "accounts" / string("accountId") / "transactions" ->
        handler { (_: String, _: Request) => respond(transactionsPrepared) },
      Method.GET / "resources" / "core" / "cards" -> handler { (_: Request) =>
        respond(cardsPrepared)
      },
      Method.GET / "resources" / "core" / "profile" -> handler { (_: Request) =>
        respond(profilePrepared)
      },
      Method.GET / "resources" / "notify" / "notifications" -> handler { (_: Request) =>
        respond(notificationsPrepared)
      },
      Method.GET / "resources" / "pay" / "templates" -> handler { (_: Request) =>
        respond(templatesPrepared)
      },
      Method.POST / "resources" / "pay" / "p2p" -> handler { (_: Request) =>
        respond(p2pPrepared)
      },
      Method.POST / "resources" / "pay" / "utility" -> handler { (_: Request) =>
        respond(utilityPrepared)
      },
      Method.PUT / "resources" / "core" / "cards" / string("cardId") / "limits" ->
        handler { (_: String, _: Request) => respond(cardLimitsPrepared) },
      Method.DELETE / "resources" / "core" / "profile" / "security" / "devices" / string("deviceId") ->
        handler { (_: String, _: Request) => respond(deviceRevocationPrepared) },
    )
