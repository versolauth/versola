package versola.loadgen.coordinator

import versola.loadgen.metrics.{CampaignReport, ErrorTaxonomy, MeasurementId}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.Instant

/** The HTTP surface of §12: the statuses an operator and a driver actually see.
  *
  * The plan service's behaviour is `CoordinatorServiceSpec`'s subject; this suite is about the
  * mapping onto HTTP -- a refusal that is a 409 rather than a 500, a malformed body that is a 400
  * rather than a crash, and a driver pointed at a non-coordinator getting an answer that says so.
  */
object CoordinatorRoutesSpec extends ZIOSpecDefault:

  private val campaign = "c3-10m-steady"

  private val t0 = Instant.parse("2026-09-15T08:00:00Z")

  private val tokenRefresh = MeasurementId.Step("mobile-otp", "token-refresh")

  private def call(
      service: Option[CoordinatorService],
      request: Request,
  ): ZIO[Scope, Throwable, Response] =
    // `handleError` stands in for the `Observability.handleErrors` that `VersolaApp` mounts in
    // front of these routes in production: a defect becomes a 500 rather than failing the effect,
    // so a test asserting on statuses cannot pass by throwing.
    CoordinatorRoutes.routes
      .handleError(failure => Response.internalServerError(failure.getMessage))
      .provideEnvironment(ZEnvironment(service))
      .runZIO(request)

  private def body[A: JsonDecoder](response: Response): ZIO[Any, Throwable, A] =
    response.body.asString.flatMap(payload => ZIO.fromEither(payload.fromJson[A]).mapError(RuntimeException(_)))

  private def coordinator =
    for
      config <- CoordinatorFixture.coordinatorConfig
      users <- FakeVirtualUsers.make()
      snapshots <- FakeMetricSnapshots.make(
        CoordinatorFixture.snapshotRow(campaign, "driver-0", t0, tokenRefresh, 90_000L, 100L),
      )
      rebalancer <- FakeRebalancer.make
      service <- CoordinatorService.make(config, users, snapshots, rebalancer, None, None).mapError(RuntimeException(_))
      _ <- TestClock.setTime(t0)
    yield service

  def spec = suite("CoordinatorRoutes")(
    test("GET /plan answers the current plan") {
      for
        service <- coordinator
        response <- call(Some(service), Request.get("/plan"))
        plan <- body[LoadPlan](response)
      yield assertTrue(
        response.status == Status.Ok,
        plan.campaign == campaign,
        plan.state == CampaignState.Idle,
        plan.shards.shardCount == 8,
      )
    },
    test("the campaign commands move the plan and answer it back") {
      for
        service <- coordinator
        started <- call(Some(service), Request.post("/campaign/start", Body.empty)).flatMap(body[LoadPlan])
        paused <- call(Some(service), Request.post("/campaign/pause", Body.empty)).flatMap(body[LoadPlan])
        stopped <- call(Some(service), Request.post("/campaign/stop", Body.empty)).flatMap(body[LoadPlan])
        refused <- call(Some(service), Request.post("/campaign/start", Body.empty))
        reason <- body[ErrorBody](refused)
      yield assertTrue(
        started.state == CampaignState.Running,
        paused.state == CampaignState.Paused,
        stopped.state == CampaignState.Stopped,
        // A conflict, not a 500: the operator asked for something this campaign cannot do, and
        // the body says which.
        refused.status == Status.Conflict,
        reason.error.contains("stopped"),
      )
    },
    test("POST /shards/rebalance publishes a drain window") {
      for
        service <- coordinator
        _ <- call(Some(service), Request.post("/campaign/start", Body.empty))
        published <- call(
          Some(service),
          Request.post("/shards/rebalance", Body.fromString(RebalanceRequest(16, 120_000L).toJson)),
        )
        plan <- body[LoadPlan](published)
      yield assertTrue(
        published.status == Status.Ok,
        plan.shards.shardCount == 8,
        plan.pendingShards.map(_.shardCount) == Some(16),
      )
    },
    test("a malformed or unsatisfiable rebalance is a 400 or a 409, never a 500") {
      for
        service <- coordinator
        malformed <- call(Some(service), Request.post("/shards/rebalance", Body.fromString("{\"shards\":16}")))
        noop <- call(
          Some(service),
          Request.post("/shards/rebalance", Body.fromString(RebalanceRequest(8, 120_000L).toJson)),
        )
      yield assertTrue(malformed.status == Status.BadRequest, noop.status == Status.Conflict)
    },
    test("GET /status answers the live view") {
      for
        service <- coordinator
        _ <- call(Some(service), Request.post("/campaign/start", Body.empty))
        response <- call(Some(service), Request.get("/status"))
        status <- body[CoordinatorStatus](response)
      yield assertTrue(
        response.status == Status.Ok,
        status.campaign == campaign,
        status.scenarios.map(_.scenario) == List(PlanScenario.MobileSession, PlanScenario.WebSession),
        status.scenarios.forall(_.achievedPerSecond.isEmpty),
        status.latency.map(_.count) == List(100L),
      )
    },
    test("GET /report/{campaign} answers a verdict, and 404s for a campaign it is not running") {
      for
        service <- coordinator
        response <- call(Some(service), Request.get(s"/report/$campaign"))
        report <- body[CampaignReport](response)
        foreign <- call(Some(service), Request.get("/report/c7-20m"))
      yield assertTrue(
        response.status == Status.Ok,
        report.campaign == campaign,
        report.drivers == List("driver-0"),
        foreign.status == Status.NotFound,
      )
    },
    test("POST /drivers/report is accepted with no body, and refuses another campaign's") {
      val report = CoordinatorFixture.driverReport(campaign, "driver-0", t0, Map.empty, ErrorTaxonomy.empty)
      for
        service <- coordinator
        accepted <- call(Some(service), Request.post("/drivers/report", Body.fromString(report.toJson)))
        foreign <- call(
          Some(service),
          Request.post("/drivers/report", Body.fromString(report.copy(campaign = "c7-20m").toJson)),
        )
        malformed <- call(Some(service), Request.post("/drivers/report", Body.fromString("not json")))
        status <- call(Some(service), Request.get("/status")).flatMap(body[CoordinatorStatus])
      yield assertTrue(
        accepted.status == Status.Accepted,
        foreign.status == Status.Conflict,
        malformed.status == Status.BadRequest,
        status.drivers == List("driver-0"),
      )
    },
    test("a process that is not the coordinator says so rather than 404ing the whole API") {
      for
        plan <- call(None, Request.get("/plan"))
        reason <- body[ErrorBody](plan)
        start <- call(None, Request.post("/campaign/start", Body.empty))
      yield assertTrue(
        plan.status == Status.ServiceUnavailable,
        reason.error.contains("coordinator role"),
        start.status == Status.ServiceUnavailable,
      )
    },
  )
