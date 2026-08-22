package versola.e2e.support

import zio.*
import zio.http.*
import zio.test.*

import java.util.UUID

object OAuthClientSpec extends ZIOSpecDefault:
  private val resourceSecret = "resource-secret-for-test"
  private val config = E2EConfig(
    authUrl = "http://auth.test",
    centralUrl = "http://central.test",
    edgeUrl = "http://edge.test",
    adminLogin = "admin",
    adminPassword = "password",
    adminNewPassword = "new-password",
    clientId = "client",
    resourceSecret = resourceSecret,
    redirectUri = "http://edge.test/complete",
  )
  private val userId = UUID.fromString("018f0f2a-1c7b-7000-9000-000000000901")

  def spec = suite("OAuthClient")(
    test("authenticates Central requests with the central resource secret") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoute(
          Method.POST / "users" -> Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(s"""{"id":"$userId"}"""))
          },
        )
        client <- ZIO.service[Client]
        result <- OAuthClient(client, config).registerUser(email = Some("user@example.com"))
        request <- seen.get.someOrFail(RuntimeException("Central request was not captured"))
      yield assertTrue(
        result == userId,
        request.header(Header.Authorization).contains(
          Header.Authorization.Basic("central", resourceSecret),
        ),
      )
    },
  ).provide(TestClient.layer)