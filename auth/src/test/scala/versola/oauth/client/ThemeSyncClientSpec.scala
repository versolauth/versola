package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.ThemeRecord
import versola.util.theme.{Mode, ThemeOverrides, ThemeTokens}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object ThemeSyncClientSpec extends ZIOSpecDefault:
  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  def spec = suite("ThemeSyncClient")(
    test("fetches themes from central") {
      val theme = ThemeRecord("default", "body { color: black; }", tenantId = None, tokens = None)
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(ThemeSyncClient.ThemesResponse(Vector(theme)).toJson))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = ThemeSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        themes <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/themes/sync"),
        themes == Vector(theme),
      )
    },
    test("reads a theme built from tokens, and a theme from a central that sends none") {
      val tokenTheme = ThemeRecord(
        "studio-dark",
        "",
        None,
        Some(ThemeTokens("studio", Some(ThemeOverrides(mode = Some(Mode.dark), brand = None, shape = None, surface = None, background = None)))),
      )
      // What a central from before tokens sends: no `tokens` key at all.
      val oldCentral = """{"themes":[{"id":"default","css":"body {}"}]}"""
      def sync(body: String) =
        for
          _ <- TestClient.addRoutes(Handler.fromFunction[Request](_ => Response.json(body)).toRoutes)
          client <- ZIO.service[Client]
          themes <- ThemeSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client)).getAll
        yield themes
      for
        withTokens <- sync(ThemeSyncClient.ThemesResponse(Vector(tokenTheme)).toJson)
        withoutTokens <- sync(oldCentral)
      yield assertTrue(withTokens == Vector(tokenTheme), withoutTokens == Vector(ThemeRecord("default", "body {}", None, None)))
    },
    test("skips a theme that cannot be read and keeps the others") {
      val good = ThemeRecord(
        "studio-dark",
        "",
        None,
        Some(ThemeTokens("studio", Some(ThemeOverrides(mode = Some(Mode.dark), brand = None, shape = None, surface = None, background = None)))),
      )
      val other = ThemeRecord("default", "body {}", None, tokens = None)
      val badMode = """{"id":"bad-mode","css":"","tokens":{"base":"studio","overrides":{"mode":"sepia"}}}"""
      val badColour = """{"id":"bad-colour","css":"","tokens":{"base":"studio","overrides":{"brand":"red"}}}"""
      val noId = """{"css":"x"}"""
      val body = s"""{"themes":[${good.toJson},$badMode,${other.toJson},$badColour,$noId]}"""
      for
        _ <- TestClient.addRoutes(Handler.fromFunction[Request](_ => Response.json(body)).toRoutes)
        client <- ZIO.service[Client]
        themes <- ThemeSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client)).getAll
      yield assertTrue(themes == Vector(good, other))
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
