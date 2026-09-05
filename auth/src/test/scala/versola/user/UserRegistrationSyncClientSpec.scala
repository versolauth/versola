package versola.user

import versola.auth.TestEnvConfig
import versola.oauth.client.CentralSyncTokenService
import versola.user.model.{Login, UserId}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.util.UUID

object UserRegistrationSyncClientSpec extends ZIOSpecDefault:
  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val event = UserRegisteredEvent(email = None, phone = None, login = Some(Login("new-user")))

  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  def spec = suite("UserRegistrationSyncClient")(
    test("claims a registration and returns the canonical user id") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(RegistrationClaimResponse(userId).toJson))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = UserRegistrationSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        result <- service.claimRegistration(event)
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
        body <- request.body.asString
      yield assertTrue(
        result == userId,
        request.method == Method.POST,
        request.url.path.encode.contains("users/registrations"),
        body == event.toJson,
      )
    },
    test("fails when central rejects the claim") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { _ =>
            ZIO.succeed(Response.status(Status.Conflict))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = UserRegistrationSyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        exit <- service.claimRegistration(event).exit
      yield assertTrue(exit.isFailure)
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
