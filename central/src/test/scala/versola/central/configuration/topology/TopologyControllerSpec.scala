package versola.central.configuration.topology

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.edges.{EdgeGroup, EdgeGroupKind, EdgeId, EdgeRecord, EdgeService}
import versola.central.configuration.resources.ResourceService
import versola.central.users.AuthClient
import versola.central.{TestAdminAuth, TestCentralConfig}
import versola.util.JWT
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.time.Instant

/** The admin API of partitions, regions and cells: what each refusal of the service is answered with, and who may call. */
object TopologyControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val kz = PartitionRecord(PartitionId("kz"))
  private val almaty = RegionRecord(RegionId("almaty"), kz.id)
  private val cellKey = TestCentralConfig.newKeyPair("kz-1-key")
  private val edgeKey = TestCentralConfig.newKeyPair("edge-1-key")

  private val edgeRecord = EdgeRecord(
    EdgeId("edge-1"),
    Some(edgeKey.toPublicJwk),
    None,
    requireDpopNonce = true,
    overloadPolicy = None,
    accessLogPolicy = None,
    rateLimitPolicies = None,
    policyMeta = None,
    groupId = EdgeGroup.DefaultId,
    groupKind = EdgeGroupKind.FirstParty,
    publicUrl = None,
  )

  private def run(effect: Task[String]): String = Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private val configuredAuthToken = run(TestCentralConfig.authToken)
  private val cellToken = run(TestCentralConfig.authTokenBy(cellKey, cellId = Some("kz-1")))
  private val edgeToken = run(
    JWT.serialize(
      JWT.Claims("edge", "edge", List("central"), Json.Obj()),
      1.minute,
      JWT.Signature.Asymmetric(JWT.Algorithm.RS256, edgeKey.keyId, edgeKey.privateKey),
      headers = Map("edge_id" -> "edge-1"),
    ),
  )

  private val cell = CellRecord(
    id = CellId("kz-1"),
    regionId = almaty.id,
    internalUrl = "http://auth-kz-1.internal:9003",
    mtlsUrl = None,
    trustedCertificates = Nil,
    state = CellState.Open,
    weight = 100,
    publicKeys = List(cellKey.toPublicJwk),
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
      registry: CellRegistry = TestCentralConfig.noCells,
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
                ZEnvironment[EdgeService](stubs.edges) ++ ZEnvironment[CellRegistry](registry) ++
                ZEnvironment[ResourceService](stubs.resources) ++
                ZEnvironment(TestCentralConfig.config) ++
                tracing,
            ),
          ),
        )
        _ <- stubs.resources.verifySecret.succeedsWith(true)
        _ <- stubs.edges.find.succeedsWith(Some(edgeRecord))
        _ <- setup(stubs)
        authed = if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request
        response <- client.batched(authed)
        result <- verify(response, stubs)
      yield assertTrue(response.status == expectedStatus) && result
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private def json(method: Method, url: URL, body: String) =
    Request(method = method, url = url, body = Body.fromString(body)).addHeader(Header.ContentType(MediaType.application.json))

  private val cells = URL.root / "configuration" / "cells"
  private val syncUrl = cells / "sync"

  def spec = suite("topology endpoints")(
    testCase(
      description = "the topology is read in one piece: partitions, regions and cells",
      request = Request.get(URL.root / "configuration" / "topology"),
      expectedStatus = Status.Ok,
      setup = stubs =>
        stubs.topology.get.succeedsWith(Topology(Vector(kz), Vector(almaty), Vector(cell), Map(EdgeId("edge-1") -> almaty.id))) *>
          stubs.topology.cellStatuses.succeedsWith(Map(cell.id -> CellStatus(users = 0, moving = 0, departures = None, drainBlocked = None))),
      verify = (response, _) =>
        for body <- response.body.asString
        yield assertTrue(
          body.fromJson[TopologyResponse] == Right(TopologyResponse(Vector(kz), Vector(almaty), Vector(CellResponse.of(cell, None)))),
        ),
    ),
    testCase(
      description = "each cell carries its users, its unfinished moves, and what it reports of its refresh tokens",
      request = Request.get(URL.root / "configuration" / "topology"),
      expectedStatus = Status.Ok,
      setup = stubs =>
        stubs.topology.get.succeedsWith(Topology(Vector(kz), Vector(almaty), Vector(cell), Map.empty)) *>
          stubs.topology.cellStatuses.succeedsWith(
            Map(
              cell.id -> CellStatus(
                users = 12,
                moving = 3,
                departures =
                  Some(AuthClient.DeparturesSummary(leaving = 1, moved = 9, tokens = 4, tokensUntil = Some(Instant.parse("2027-01-01T00:00:00Z")))),
                drainBlocked = Some("no_open_cell"),
              ),
            ),
          ),
      verify = (response, _) =>
        for body <- response.body.asString.map(_.fromJson[Json.Obj].toOption.get)
        yield
          val kz1 = body.fields.collectFirst { case ("cells", Json.Arr(cells)) => cells.head.asInstanceOf[Json.Obj] }.get
          def field(name: String) = kz1.fields.collectFirst { case (`name`, value) => value }
          assertTrue(
            field("users").contains(Json.Num(12)),
            field("moving").contains(Json.Num(3)),
            field("tokens").contains(Json.Num(4)),
            field("tokensUntil").contains(Json.Str("2027-01-01T00:00:00Z")),
            field("drainBlocked").contains(Json.Str("no_open_cell")),
            // the record's own fields are where they were
            field("id").contains(Json.Str("kz-1")),
            field("state").contains(Json.Str("open")),
          ),
    ),
    testCase(
      description = "a cell that did not answer has no tokens and no tokensUntil in the answer, and the request is not an error",
      request = Request.get(URL.root / "configuration" / "topology"),
      expectedStatus = Status.Ok,
      setup = stubs =>
        stubs.topology.get.succeedsWith(Topology(Vector(kz), Vector(almaty), Vector(cell), Map.empty)) *>
          stubs.topology.cellStatuses.succeedsWith(Map(cell.id -> CellStatus(users = 5, moving = 0, departures = None, drainBlocked = None))),
      verify = (response, _) =>
        for body <- response.body.asString.map(_.fromJson[Json.Obj].toOption.get)
        yield
          val kz1 = body.fields.collectFirst { case ("cells", Json.Arr(cells)) => cells.head.asInstanceOf[Json.Obj] }.get
          val names = kz1.fields.map(_._1).toSet
          assertTrue(names.contains("users"), !names.contains("tokens"), !names.contains("tokensUntil"), !names.contains("drainBlocked")),
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
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.topology.deleteCell.calls == List((cell.id, false, false)))),
    ),
    testCase(
      description = "a cell said to be lost is deleted as lost, and any other value of the parameter is not that",
      request = Request.delete(cells.addQueryParam("cellId", "kz-1").addQueryParam("lost", "true")),
      expectedStatus = Status.NoContent,
      setup = _.topology.deleteCell.succeedsWith(()),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.topology.deleteCell.calls == List((cell.id, true, false)))),
    ),
    testCase(
      description = "lost=yes is not lost=true: the cell is deleted only if it holds no user",
      request = Request.delete(cells.addQueryParam("cellId", "kz-1").addQueryParam("lost", "yes")),
      expectedStatus = Status.NoContent,
      setup = _.topology.deleteCell.succeedsWith(()),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.topology.deleteCell.calls == List((cell.id, false, false)))),
    ),
    testCase(
      description = "force=true is passed on, and any other value of the parameter is not that",
      request = Request.delete(cells.addQueryParam("cellId", "kz-1").addQueryParam("force", "true")),
      expectedStatus = Status.NoContent,
      setup = _.topology.deleteCell.succeedsWith(()),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.topology.deleteCell.calls == List((cell.id, false, true)))),
    ),
    testCase(
      description = "force=1 is not force=true",
      request = Request.delete(cells.addQueryParam("cellId", "kz-1").addQueryParam("force", "1")),
      expectedStatus = Status.NoContent,
      setup = _.topology.deleteCell.succeedsWith(()),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.topology.deleteCell.calls == List((cell.id, false, false)))),
    ),
    testCase(
      description = "a delete refused because of an unfinished move or the tokens a cell still answers for is 409",
      request = Request.delete(cells.addQueryParam("cellId", "kz-1")),
      expectedStatus = Status.Conflict,
      setup = _.topology.deleteCell.failsWith(TopologyError.Conflict("cell 'kz-1' still answers for 4 refresh token(s)")),
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
    testCase(
      description = "a cell with no publicKeys member is not a cell: the keys are required, not defaulted",
      request = json(Method.POST, cells, cell.toJson.replaceAll(""","publicKeys":\[.*\]""", "")),
      expectedStatus = Status.BadRequest,
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.topology.createCell.calls.isEmpty)),
    ),
    testCase(
      description = "the keys of a cell are handed to the service as they were sent, and written back as they are stored",
      request = json(Method.POST, cells, cell.toJson),
      expectedStatus = Status.Created,
      setup = _.topology.createCell.succeedsWith(()),
      verify = (_, stubs) =>
        ZIO.succeed(assertTrue(stubs.topology.createCell.calls.map(_.publicKeys) == List(List(cellKey.toPublicJwk)))),
    ),
    // `GET /configuration/cells/sync`: the cells as an auth knows them (#1050)
    testCase(
      description = "the cells an auth is told of: id, state, weight, region and public keys, and nothing of addresses or pins",
      request = Request.get(syncUrl).addHeader(Header.Authorization.Bearer(configuredAuthToken)),
      authorized = false,
      registry = TestCentralConfig.registryOf(cell.copy(mtlsUrl = Some("https://auth-kz-1.internal:9443"))),
      expectedStatus = Status.Ok,
      verify = (response, _) =>
        for body <- response.body.asString
        yield assertTrue(
          body.fromJson[Json] == Right(
            Json.Obj(
              "cells" -> Json.Arr(
                Json.Obj(
                  "id" -> Json.Str("kz-1"),
                  "state" -> Json.Str("open"),
                  "weight" -> Json.Num(100),
                  "regionId" -> Json.Str("almaty"),
                  "publicKeys" -> Json.Arr(cellKey.toPublicJwk),
                ),
              ),
            ),
          ),
        ),
    ),
    testCase(
      description = "an auth of a registered cell may read the cells",
      request = Request.get(syncUrl).addHeader(Header.Authorization.Bearer(cellToken)),
      authorized = false,
      registry = TestCentralConfig.registryOf(cell),
      expectedStatus = Status.Ok,
    ),
    testCase(
      description = "an edge is refused the cells an auth reads, whatever its token proves",
      request = Request.get(syncUrl).addHeader(Header.Authorization.Bearer(edgeToken)),
      authorized = false,
      registry = TestCentralConfig.registryOf(cell),
      expectedStatus = Status.Unauthorized,
    ),
    testCase(
      description = "a token of a cell that is not registered, so a key that is nowhere, is refused the cells",
      request = Request.get(syncUrl).addHeader(Header.Authorization.Bearer(cellToken)),
      authorized = false,
      expectedStatus = Status.Unauthorized,
    ),
    testCase(
      description = "the admin Basic credential does not read the cells an auth reads",
      request = Request.get(syncUrl),
      expectedStatus = Status.Unauthorized,
    ),
  )
