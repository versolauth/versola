package versola.central.antifraud

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.central.TestCentralConfig
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.topology.CellRegistry
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
import java.util.UUID

object AntifraudPolicyControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val limit = VelocityLimitDto(perMinute = 0, perTenMinutes = 0, perHour = 3, perDay = 0, weight = 50)
  private val settings = AntifraudSettings(
    mode = AntifraudMode.enforce,
    challengeAt = 0,
    blockAt = 80,
    pow = PowDifficultyDto(14, 18, 15),
    thresholds = VelocityThresholdsDto(limit, limit, limit, limit, limit, 40),
    smsLimits = SmsLimitsDto(SmsLimitDto(10, 0, 0.1), Map.empty),
    ruleWeights = Map("velocity_phone_1h" -> 40),
    shadowRules = Set("velocity_ip_1m"),
  )
  private val state = PolicyState(settingsVersion = 4, settings = Some(settings), listsVersion = 11)
  private val lists = PolicyLists(
    version = 11,
    entries = List(
      ListEntryRecord(
        id = UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
        listType = AntifraudListType.deny,
        valueType = AntifraudValueType.prefix,
        value = "4915",
        tenantId = Some(TenantId("tenant-a")),
        expiresAt = Some(Instant.parse("2026-12-01T00:00:00Z")),
        reason = Some("attack"),
        source = "manual",
        shadow = true,
        createdBy = "alice",
        createdAt = Instant.parse("2026-11-20T10:00:00Z"),
      ),
    ),
  )

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def token(secret: JWT.Signature, claims: JWT.Claims = JWT.Claims("auth", "auth", List("central"), Json.Obj())) =
    JWT.serialize(claims = claims, ttl = 10.minutes, signature = secret)

  /** What auth presents: signed with the key central is configured with for its auth, as its sync token is. */
  private val authToken = token(TestCentralConfig.authSignature)

  private val sync = URL.root / "antifraud" / "policy" / "sync"
  private val listsSync = URL.root / "antifraud" / "policy" / "lists" / "sync"

  private def run(request: Request, settingsService: AntifraudSettingsService, listService: AntifraudListService) =
    for
      client <- ZIO.service[Client]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudPolicyController.routes.provideEnvironment(
            ZEnvironment[AntifraudSettingsService](settingsService) ++
              ZEnvironment[AntifraudListService](listService) ++
              ZEnvironment[EdgeService](stub[EdgeService]) ++
              ZEnvironment[CellRegistry](TestCentralConfig.noCells) ++
              ZEnvironment(TestCentralConfig.config) ++
              tracing,
          ),
        ),
      )
      response <- client.batched(request)
    yield response

  def spec = suite("AntifraudPolicyController")(
    test("the state endpoint answers the settings, their version and the version of the lists to auth's token") {
      val settingsService = stub[AntifraudSettingsService]
      val listService = stub[AntifraudListService]
      for
        _ <- settingsService.state.succeedsWith(state)
        raw <- authToken
        response <- run(Request.get(sync).addHeader(Header.Authorization.Bearer(raw)), settingsService, listService)
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.fromJson[PolicyState] == Right(state),
        listService.active.calls.isEmpty,
      )
    },
    test("the lists endpoint answers the entries that have not expired and the version they were read at") {
      val settingsService = stub[AntifraudSettingsService]
      val listService = stub[AntifraudListService]
      for
        _ <- listService.active.succeedsWith(lists)
        raw <- authToken
        response <- run(Request.get(listsSync).addHeader(Header.Authorization.Bearer(raw)), settingsService, listService)
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.fromJson[PolicyLists] == Right(lists),
        settingsService.state.calls.isEmpty,
      )
    },
    test("a request without a token is refused and nothing is read") {
      val settingsService = stub[AntifraudSettingsService]
      val listService = stub[AntifraudListService]
      for
        state <- run(Request.get(sync), settingsService, listService)
        lists <- run(Request.get(listsSync), settingsService, listService)
      yield assertTrue(
        state.status == Status.Unauthorized,
        lists.status == Status.Unauthorized,
        settingsService.state.calls.isEmpty,
        listService.active.calls.isEmpty,
      )
    } @@ TestAspect.silentLogging,
    test("a token signed with a key nobody registered, a malformed one and a Basic credential are refused") {
      val settingsService = stub[AntifraudSettingsService]
      val listService = stub[AntifraudListService]
      for
        foreign <- token(TestCentralConfig.foreignSignature)
        wrongKey <- run(Request.get(listsSync).addHeader(Header.Authorization.Bearer(foreign)), settingsService, listService)
        garbage <- run(Request.get(sync).addHeader(Header.Authorization.Bearer("not-a-jwt")), settingsService, listService)
        basic <- run(Request.get(sync).addHeader(Header.Authorization.Basic("admin", "admin")), settingsService, listService)
      yield assertTrue(
        wrongKey.status == Status.Unauthorized,
        garbage.status == Status.Unauthorized,
        basic.status == Status.Unauthorized,
        settingsService.state.calls.isEmpty,
        listService.active.calls.isEmpty,
      )
    } @@ TestAspect.silentLogging,
  ).provideSomeLayer(TestClient.layer)
