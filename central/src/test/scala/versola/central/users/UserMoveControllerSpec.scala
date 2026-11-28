package versola.central.users

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.TestAdminAuth
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.topology.CellId
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.time.Instant
import java.util.UUID

/** The admin API of moving a user between cells (versolauth/versola#1051): every answer of the contract. */
object UserMoveControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private case class Stubs(moves: Stub[UserMoveService], resources: Stub[ResourceService])

  private def post(body: String) =
    Request(method = Method.POST, url = URL.root / "users" / "move", body = Body.fromString(body))
      .addHeader(Header.ContentType(MediaType.application.json))

  private def testCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      authorized: Boolean = true,
      setup: Stubs => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stubs) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      val stubs = Stubs(stub[UserMoveService], stub[ResourceService])
      for
        client <- ZIO.service[Client]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserMoveController.routes.provideEnvironment(
              ZEnvironment[UserMoveService](stubs.moves) ++ ZEnvironment[ResourceService](stubs.resources) ++ tracing,
            ),
          ),
        )
        _ <- stubs.resources.verifySecret.succeedsWith(true)
        _ <- setup(stubs)
        authed = if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request
        response <- client.batched(authed)
        result <- verify(response, stubs)
      yield assertTrue(response.status == expectedStatus) && result
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val startBody = s"""{"userId":"$userId","cell":"kz-2"}"""

  private def moveOf(phase: MovePhase) =
    UserMove(
      userId = userId,
      fromCell = CellId("kz-1"),
      toCell = CellId("kz-2"),
      phase = phase,
      startedAt = Instant.parse("2027-01-01T00:00:00Z"),
      updatedAt = Instant.parse("2027-01-01T00:00:05Z"),
      attempts = 2,
      error = Some("arrive_failed"),
      reason = MoveReason.Drain,
      notBefore = Instant.parse("2027-01-01T00:00:09Z"),
      departedAt = None,
      moveId = UUID.fromString("00000000-0000-7000-8000-0000000000a1"),
      digest = None,
    )

  private def refusal(description: String, refusal: MoveRefusal, status: Status) =
    testCase(
      description = description,
      request = post(startBody),
      expectedStatus = status,
      setup = _.moves.start.failsWith(refusal),
      verify = (response, _) => response.body.asString.map(body => assertTrue(body == refusal.message)),
    )

  def spec = suite("move endpoints")(
    testCase(
      description = "a move is started and answered 202 with nothing in the body",
      request = post(startBody),
      expectedStatus = Status.Accepted,
      setup = _.moves.start.succeedsWith(()),
      verify = (response, stubs) =>
        response.body.asString.map(body => assertTrue(body.isEmpty, stubs.moves.start.calls == List((userId, CellId("kz-2"))))),
    ),
    refusal("an unknown user is 404", MoveRefusal.NoSuchUser, Status.NotFound),
    refusal("a user in no cell is 400", MoveRefusal.NoCell, Status.BadRequest),
    refusal("a target that is not registered is 400", MoveRefusal.TargetNotRegistered, Status.BadRequest),
    refusal("the user's own cell is 400", MoveRefusal.SameCell, Status.BadRequest),
    refusal("a cell of another partition is 400", MoveRefusal.OtherPartition, Status.BadRequest),
    refusal("a draining cell is 400", MoveRefusal.TargetDraining, Status.BadRequest),
    refusal("a source cell that is not registered is 400", MoveRefusal.SourceNotRegistered, Status.BadRequest),
    refusal("a move in progress is 409", MoveRefusal.InProgress, Status.Conflict),
    testCase(
      description = "a body that is not a user and a cell is refused before the service is asked",
      request = post("""{"userId":"not-a-uuid","cell":"kz-2"}"""),
      expectedStatus = Status.BadRequest,
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.moves.start.calls.isEmpty)),
    ),
    testCase(
      description = "a cell id that is not one is refused before the service is asked",
      request = post(s"""{"userId":"$userId","cell":"KZ 2"}"""),
      expectedStatus = Status.BadRequest,
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.moves.start.calls.isEmpty)),
    ),
    testCase(
      description = "starting a move without the console's credential is 401, and nothing is asked",
      request = post(startBody),
      authorized = false,
      expectedStatus = Status.Unauthorized,
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.moves.start.calls.isEmpty)),
    ),
    testCase(
      description = "the moves are listed with their phase, reason, attempts and error, and the default page size",
      request = Request.get(URL.root / "users" / "moves"),
      expectedStatus = Status.Ok,
      setup = _.moves.list.succeedsWith(Vector(moveOf(MovePhase.Exported))),
      verify = (response, stubs) =>
        for body <- response.body.asString
        yield assertTrue(
          body.fromJson[UserMovesResponse] == Right(UserMovesResponse(Vector(UserMoveResponse.of(moveOf(MovePhase.Exported))))),
          stubs.moves.list.calls == List((None, None, 100)),
          body.contains(""""phase":"exported""""),
          body.contains(""""reason":"drain""""),
          body.contains(""""from":"kz-1""""),
          body.contains(""""to":"kz-2""""),
          // the id that names the move at both cells is told, the digest of the rows is not
          body.contains(""""moveId":"00000000-0000-7000-8000-0000000000a1""""),
          !body.contains("digest"),
          // what only the processor needs is not told
          !body.contains("notBefore"),
        ),
    ),
    testCase(
      description = "the moves can be asked for by cell and phase and page size",
      request = Request.get(URL.root / "users" / "moves").updateURL(_.addQueryParams("cell=kz-1&phase=flipped&limit=7")),
      expectedStatus = Status.Ok,
      setup = _.moves.list.succeedsWith(Vector.empty),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.moves.list.calls == List((Some(CellId("kz-1")), Some(MovePhase.Flipped), 7)))),
    ),
    testCase(
      description = "a phase that does not exist is 400",
      request = Request.get(URL.root / "users" / "moves").updateURL(_.addQueryParams("phase=sleeping")),
      expectedStatus = Status.BadRequest,
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.moves.list.calls.isEmpty)),
    ),
    testCase(
      description = "a page size outside 1 to 500 is 400",
      request = Request.get(URL.root / "users" / "moves").updateURL(_.addQueryParams("limit=0")),
      expectedStatus = Status.BadRequest,
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.moves.list.calls.isEmpty)),
    ),
    testCase(
      description = "listing the moves without the console's credential is 401",
      request = Request.get(URL.root / "users" / "moves"),
      authorized = false,
      expectedStatus = Status.Unauthorized,
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.moves.list.calls.isEmpty)),
    ),
  )
