package versola.central.configuration.themes

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.TestCentralConfig
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, TestAdminAuth}
import versola.util.JWT
import versola.util.http.Observability
import versola.util.theme.VariableValue.Single
import versola.util.theme.{BrandColor, Mode, ThemeOverrides, ThemePresets, ThemeTokens, ThemeVariableCatalog, ThemeVariables}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

object ThemeControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val tenantId = TenantId("t1")
  private val themeId = "theme-1"
  private val themeRecord = ThemeRecord(themeId, "body { color: red; }", Some(tenantId), tokens = None)

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val config = TestCentralConfig.config

  private val syncToken = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          JWT.Claims("a", "b", List("c"), Json.Obj()),
          1.minute,
          JWT.Signature.Symmetric(config.secretKey),
        ),
      )
      .getOrThrowFiberFailure()
  }

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[ThemeService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[ThemeService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        themeService = stub[ThemeService]
        resourceService = stub[ResourceService]
        edgeService = stub[EdgeService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ThemeController.routes.provideEnvironment(
              ZEnvironment[ThemeService](themeService) ++
                ZEnvironment[ResourceService](resourceService) ++
                ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[CentralConfig](config) ++
                tracing,
            ),
          ),
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(themeService)
        requestWithAuth = request.headers.header(Header.Authorization) match
          case None => request.addHeader(TestAdminAuth.basicAuthHeader)
          case _ => request
        response <- client.batched(requestWithAuth)
        verifyResult <- verify(response, themeService)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("ThemeController")(
    controllerTestCase(
      description = "get themes returns 200 OK",
      request = Request.get(
        (URL.root / "configuration" / "themes").addQueryParam("tenantId", tenantId.toString),
      ),
      expectedStatus = Status.Ok,
      setup = service => service.getThemes.succeedsWith(Vector(themeRecord)),
    ),
    controllerTestCase(
      description = "delete theme returns 204 No Content",
      request = Request.delete(
        (URL.root / "configuration" / "themes").addQueryParam("id", themeId),
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteTheme.succeedsWith(()),
    ),
    controllerTestCase(
      description = "delete theme returns 409 Conflict when the theme is in use",
      request = Request.delete(
        (URL.root / "configuration" / "themes").addQueryParam("id", themeId),
      ),
      expectedStatus = Status.Conflict,
      setup = service => service.deleteTheme.failsWith(new ThemeService.ThemeInUseError),
    ),
    controllerTestCase(
      description = "delete theme returns 400 Bad Request when deleting the default theme",
      request = Request.delete(
        (URL.root / "configuration" / "themes").addQueryParam("id", ThemeService.DefaultThemeId),
      ),
      expectedStatus = Status.BadRequest,
      setup = service => service.deleteTheme.failsWith(new IllegalArgumentException("Cannot delete the default theme")),
    ),
    controllerTestCase(
      description = "create theme returns 201 Created",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "themes",
        body = Body.fromString(CreateThemeRequest(themeId, "body { color: green; }", Some(tenantId), tokens = None).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.createTheme.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.createTheme.calls == List(ThemeRecord(themeId, "body { color: green; }", Some(tenantId), tokens = None)),
        )),
    ),
    controllerTestCase(
      description = "update theme returns 204 No Content",
      request = Request(
        method = Method.PUT,
        url = URL.root / "configuration" / "themes",
        body = Body.fromString(UpdateThemeRequest(themeId, "body { color: yellow; }", tokens = None).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.updateTheme.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.updateTheme.calls == List(ThemeRecord(themeId, "body { color: yellow; }", None, tokens = None)),
        )),
    ),
    controllerTestCase(
      description = "create theme passes its tokens on",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "themes",
        body = Body.fromString(CreateThemeRequest(
          themeId,
          "",
          Some(tenantId),
          Some(ThemeTokens(base = "studio", overrides = None, variables = None)),
        ).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.createTheme.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.createTheme.calls == List(ThemeRecord(
          themeId,
          "",
          Some(tenantId),
          Some(ThemeTokens(base = "studio", overrides = None, variables = None)),
        )))),
    ),
    controllerTestCase(
      description = "create theme answers 400 with the reason when its tokens name no built-in base",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "themes",
        body = Body.fromString(CreateThemeRequest(themeId, "", None, Some(ThemeTokens(base = "nope", overrides = None, variables = None))).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service => service.createTheme.failsWith(new IllegalArgumentException("'nope' is not a built-in theme")),
      verify = (response, _) => response.body.asString.map(body => assertTrue(body.contains("not a built-in theme"))),
    ),
    controllerTestCase(
      description = "create theme refuses a brand colour that is not #rrggbb before it reaches the service",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "themes",
        body = Body.fromString("""{"id":"x","css":"","tokens":{"base":"studio","overrides":{"brand":"#ff0000;}body{display:none"}}}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      verify = (_, service) => ZIO.succeed(assertTrue(service.createTheme.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "update theme answers 400 when its tokens are refused",
      request = Request(
        method = Method.PUT,
        url = URL.root / "configuration" / "themes",
        body = Body.fromString(UpdateThemeRequest(themeId, "", Some(ThemeTokens(base = "nope", overrides = None, variables = None))).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service => service.updateTheme.failsWith(new IllegalArgumentException("'nope' is not a built-in theme")),
    ),
    controllerTestCase(
      description = "create theme passes the token overrides to the service, and answers 400 with the reason when they are refused",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "themes",
        body = Body.fromString("""{"id":"x","css":"","tokens":{"base":"studio","variables":{"--rs":"8px; }"}}}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service => service.createTheme.failsWith(new IllegalArgumentException("--rs contains something a value may not")),
      verify = (response, service) =>
        response.body.asString.map(body =>
          assertTrue(
            body.contains("--rs contains"),
            service.createTheme.calls.flatMap(_.tokens).flatMap(_.variables) == List(Map("--rs" -> Single("8px; }"))),
          ),
        ),
    ),
    controllerTestCase(
      description = "GET /configuration/themes/presets lists the built-in themes",
      request = Request.get(URL.root / "configuration" / "themes" / "presets"),
      expectedStatus = Status.Ok,
      verify = (response, _) =>
        for body <- response.body.asString
        yield assertTrue(body.fromJson[GetThemePresetsResponse] == Right(GetThemePresetsResponse(ThemePresets.all))),
    ),
    controllerTestCase(
      description = "GET /configuration/themes/variables lists the tokens a theme may set, grouped and described in each language",
      request = Request.get(URL.root / "configuration" / "themes" / "variables"),
      expectedStatus = Status.Ok,
      verify = (response, _) =>
        for body <- response.body.asString
        yield assertTrue(
          body.fromJson[ThemeVariableCatalog] == Right(ThemeVariables.catalog),
          ThemeVariables.catalog.variables.exists(_.name == "--btn"),
        ),
    ),
    controllerTestCase(
      description = "POST /configuration/themes/css returns the stylesheet of the tokens",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "themes" / "css",
        body = Body.fromString(RenderThemeRequest(
          Some(ThemeTokens(base = "fintech", overrides = None, variables = None)),
          Some("x{}"),
          overrides = None,
        ).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Ok,
      setup = service => service.render.succeedsWith(RenderThemeResponse("full", "gen")),
      verify = (response, service) =>
        for body <- response.body.asString
        yield assertTrue(
          service.render.calls == List(RenderThemeRequest(
            Some(ThemeTokens(base = "fintech", overrides = None, variables = None)),
            Some("x{}"),
            overrides = None,
          )),
          body.fromJson[RenderThemeResponse] == Right(RenderThemeResponse("full", "gen")),
        ),
    ),
    controllerTestCase(
      description = "POST /configuration/themes/css takes tokens without overrides and a request without css",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "themes" / "css",
        body = Body.fromString("""{"tokens":{"base":"fintech"}}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Ok,
      setup = service => service.render.succeedsWith(RenderThemeResponse("full", "gen")),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.render.calls == List(RenderThemeRequest(
          Some(ThemeTokens(base = "fintech", overrides = None, variables = None)),
          css = None,
          overrides = None,
        )))),
    ),
    controllerTestCase(
      description = "POST /configuration/themes/css takes a theme with CSS only (no tokens) with a client's overrides",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "themes" / "css",
        body = Body.fromString("""{"css":"x{}","overrides":{"brand":"#112233","mode":"dark"}}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Ok,
      setup = service => service.render.succeedsWith(RenderThemeResponse("full", "")),
      verify = (_, service) =>
        ZIO.succeed(
          assertTrue(
            service.render.calls == List(
              RenderThemeRequest(
                tokens = None,
                css = Some("x{}"),
                overrides = Some(ThemeOverrides(BrandColor.parse("#112233").toOption, None, None, Some(Mode.dark), None)),
              ),
            ),
          ),
        ),
    ),
    controllerTestCase(
      description = "POST /configuration/themes/css answers 400 for an override that is not allowed",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "themes" / "css",
        body = Body.fromString("""{"tokens":{"base":"fintech"},"overrides":{"brand":"red; } body { display:none"}}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
    ),
    controllerTestCase(
      description = "POST /configuration/themes/css answers 400 for a base that is not built in",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "themes" / "css",
        body = Body.fromString(RenderThemeRequest(
          Some(ThemeTokens(base = "nope", overrides = None, variables = None)),
          css = None,
          overrides = None,
        ).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service => service.render.failsWith(new IllegalArgumentException("'nope' is not a built-in theme")),
    ),
    controllerTestCase(
      description = "POST /configuration/themes/css refuses a service token, which is not the admin's basic credential",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "themes" / "css",
        body = Body.fromString(RenderThemeRequest(
          Some(ThemeTokens(base = "studio", overrides = None, variables = None)),
          css = None,
          overrides = None,
        ).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)).addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Unauthorized,
    ),
    controllerTestCase(
      description = "sync themes returns 200 OK for an authorized service token",
      request = Request.get(URL.root / "configuration" / "themes" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service => service.getAllThemes.succeedsWith(Vector(themeRecord)),
      verify = (response, service) =>
        for payload <- response.body.asJson[GetAllThemesResponse]
        yield assertTrue(
          service.getAllThemes.calls.length == 1,
          payload == GetAllThemesResponse(Vector(themeRecord)),
        ),
    ),
  )
