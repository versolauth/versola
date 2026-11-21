package versola.central.configuration.topology

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.edges.{EdgeId, EdgeService}
import versola.central.configuration.resources.ResourceService
import versola.central.{TestAdminAuth, TestCentralConfig}
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

/** The admin API of partitions, regions and cells: what each refusal of the service is answered with, and who may call. */
object TopologyControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val kz = PartitionRecord(PartitionId("kz"))
  private val almaty = RegionRecord(RegionId("almaty"), kz.id)
  private val cell = CellRecord(
    id = CellId("kz-1"),
    regionId = almaty.id,
    internalUrl = "http://auth-kz-1.internal:9003",
    mtlsUrl = None,
    trustedCertificates = Nil,
    state = CellState.Open,
    weight = 100,
  )

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private case class Stubs(topology: Stub[TopologyService], edges: Stub[EdgeService], resources: Stub[ResourceService])

  private def testCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      authorized: Boolean = true,
      setup: Stubs => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stubs) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      val stubs = Stubs(stub[TopologyService], stub[EdgeService], stub[ResourceService])
      for
        client <- ZIO.service[Client]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            TopologyController.routes.provideEnvironment(
              ZEnvironment[TopologyService](stubs.topology) ++
                ZEnvironment[EdgeService](stubs.edges) ++
                ZEnvironment[ResourceService](stubs.resources) ++
                ZEnvironment(TestCentralConfig.config) ++
                tracing,
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

  private def json(method: Method, url: URL, body: String) =
    Request(method = method, url = url, body = Body.fromString(body)).addHeader(Header.ContentType(MediaType.application.json))

  private val cells = URL.root / "configuration" / "cells"

  def spec = suite("topology endpoints")(
    testCase(
      description = "the topology is read in one piece: partitions, regions and cells",
      request = Request.get(URL.root / "configuration" / "topology"),
      expectedStatus = Status.Ok,
      setup = _.topology.get.succeedsWith(Topology(Vector(kz), Vector(almaty), Vector(cell), Map(EdgeId("edge-1") -> almaty.id))),
      verify = (response, _) =>
        for body <- response.body.asString
        yield assertTrue(body.fromJson[TopologyResponse] == Right(TopologyResponse(Vector(kz), Vector(almaty), Vector(cell)))),
    ),
    testCase(
      description = "a cell is created and answered 201",
      request = json(Method.POST, cells, cell.toJson),
      expectedStatus = Status.Created,
      setup = _.topology.createCell.succeedsWith(()),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.topology.createCell.calls == List(cell))),
    ),
    testCase(
      description = "what the service finds malformed is 400 with the reason",
      request = json(Method.POST, cells, cell.toJson),
      expectedStatus = Status.BadRequest,
      setup = _.topology.createCell.failsWith(TopologyError.Invalid("weight must not be negative")),
      verify = (response, _) => response.body.asString.map(body => assertTrue(body == "weight must not be negative")),
    ),
    testCase(
      description = "what is stored already is 409",
      request = json(Method.POST, cells, cell.toJson),
      expectedStatus = Status.Conflict,
      setup = _.topology.createCell.failsWith(TopologyError.Conflict("cell 'kz-1' already exists")),
    ),
    testCase(
      description = "an update of a cell that does not exist is 404",
      request = json(Method.PUT, cells, cell.toJson),
      expectedStatus = Status.NotFound,
      setup = _.topology.updateCell.failsWith(TopologyError.Missing("cell 'kz-1' does not exist")),
    ),
    testCase(
      description = "an id or a state the model does not allow is refused before the service is asked",
      request = json(Method.POST, cells, cell.toJson.replace("\"open\"", "\"paused\"")),
      expectedStatus = Status.BadRequest,
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.topology.createCell.calls.isEmpty)),
    ),
    testCase(
      description = "a cell is deleted by its id",
      request = Request.delete(cells.addQueryParam("cellId", "kz-1")),
      expectedStatus = Status.NoContent,
      setup = _.topology.deleteCell.succeedsWith(()),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.topology.deleteCell.calls == List(cell.id))),
    ),
    testCase(
      description = "an edge is put in a region, and a body that names none takes it out",
      request = json(Method.PUT, (URL.root / "configuration" / "edges" / "region").addQueryParam("edgeId", "edge-1"), "{}"),
      expectedStatus = Status.NoContent,
      setup = _.topology.setEdgeRegion.succeedsWith(()),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.topology.setEdgeRegion.calls == List((EdgeId("edge-1"), None)))),
    ),
    testCase(
      description = "changing the topology needs the admin credential",
      request = json(Method.POST, cells, cell.toJson),
      authorized = false,
      expectedStatus = Status.Unauthorized,
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.topology.createCell.calls.isEmpty)),
    ),
  )
