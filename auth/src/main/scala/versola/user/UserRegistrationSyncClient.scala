package versola.user

import versola.oauth.client.CentralSyncTokenService
import versola.util.CoreConfig
import zio.http.{Body, Header, MediaType, Method, Request, Status}
import zio.json.*
import zio.{Task, URLayer, ZIO, ZLayer}

/** Reports self-service registrations to central so its user index stays complete. */
trait UserRegistrationSyncClient:
  def reportRegistration(event: UserRegisteredEvent): Task[Unit]

object UserRegistrationSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, UserRegistrationSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends UserRegistrationSyncClient:
    private val RegistrationsUrl = config.central.url / "service" / "users" / "registrations"

    override def reportRegistration(event: UserRegisteredEvent): Task[Unit] =
      ZIO.scoped:
        for
          response <- centralSyncTokenService.syncRequest(
                        Request
                          .post(RegistrationsUrl, Body.fromString(event.toJson))
                          .addHeader(Header.ContentType(MediaType.application.json)),
                      )
          _ <- ZIO.unless(response.status.isSuccess):
                 // Retried by the outbox: failing here leaves the row queued with a bumped attempt.
                 ZIO.fail(new Exception(s"Reporting registration failed with ${response.status}"))
        yield ()
