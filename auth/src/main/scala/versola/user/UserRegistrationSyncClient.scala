package versola.user

import versola.oauth.client.CentralSyncTokenService
import versola.user.model.UserId
import versola.util.CoreConfig
import zio.http.{Body, Header, MediaType, Method, Request, Status}
import zio.json.*
import zio.{Task, URLayer, ZIO, ZLayer}

/** Claims self-service registration credentials in central, returning the canonical user ID. */
trait UserRegistrationSyncClient:
  def claimRegistration(event: UserRegisteredEvent): Task[UserId]

object UserRegistrationSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, UserRegistrationSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends UserRegistrationSyncClient:
    private val RegistrationsUrl = config.central.url / "users" / "registrations"

    override def claimRegistration(event: UserRegisteredEvent): Task[UserId] =
      ZIO.scoped:
        for
          response <- centralSyncTokenService.syncRequest(
            Request
              .post(RegistrationsUrl, Body.fromString(event.toJson))
              .addHeader(Header.ContentType(MediaType.application.json)),
          )
          _ <- ZIO.unless(response.status.isSuccess):
            ZIO.fail(new Exception(s"Claiming registration failed with ${response.status}"))
          claim <- response.body.asJsonFromCodec[RegistrationClaimResponse]
        yield claim.userId
