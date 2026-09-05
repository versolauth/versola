package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.{BooleanProperty, FormRecord}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object FormSyncClientSpec extends ZIOSpecDefault:
  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  def spec = suite("FormSyncClient")(
    test("fetches forms from central") {
      val form = FormRecord(
        id = "signup",
        version = 1,
        active = true,
        style = "default",
        jsSource = None,
        jsCompiled = None,
        localizations = Map.empty,
        properties = Vector(BooleanProperty("marketingOptIn")),
      )
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(FormSyncClient.FormsResponse(Vector(form)).toJson))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = FormSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        forms <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/forms/sync"),
        forms == Vector(form),
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
