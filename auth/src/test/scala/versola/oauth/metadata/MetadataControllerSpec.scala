package versola.oauth.metadata

import versola.oauth.client.OAuthConfigurationService
import versola.util.UnitSpecBase
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object MetadataControllerSpec extends UnitSpecBase:

  private def testCase(
      description: String,
      name: String,
      metadata: Json.Obj,
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        configuration = stub[OAuthConfigurationService]
        _ <- TestClient.addRoutes(
          Observability.handleErrors(MetadataController.routes.provideEnvironment(ZEnvironment(configuration)))
        )
        _ <- configuration.getMetadata.succeedsWith(metadata)

        response <- client.batched(Request.get(URL.empty / ".well-known" / name))
        body <- response.body.asString
        actualJson <- ZIO.fromEither(body.fromJson[Json]).mapError(new RuntimeException(_))
      yield assertTrue(
        response.status == Status.Ok,
        actualJson == metadata,
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("MetadataController")(
    testCase(
      description = "GET /.well-known/oauth-authorization-server returns the configuration service's metadata",
      name = "oauth-authorization-server",
      metadata = Json.Obj("issuer" -> Json.Str("https://auth.example.com")),
    ),
    testCase(
      description = "GET /.well-known/openid-configuration returns the configuration service's metadata",
      name = "openid-configuration",
      metadata = Json.Obj("issuer" -> Json.Str("https://auth.example.com")),
    ),
  )
