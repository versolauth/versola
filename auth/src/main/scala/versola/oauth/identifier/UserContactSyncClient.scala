package versola.oauth.identifier

import versola.oauth.client.CentralSyncTokenService
import versola.user.given
import versola.user.model.UserId
import versola.util.{CoreConfig, Email, Phone}
import zio.http.{Body, Header, MediaType, Request, Status}
import zio.json.*
import zio.{Task, URLayer, ZIO, ZLayer}

import java.util.UUID

enum UserContactResult:
  /** Central updated its user index; `version` orders the change against the outbox events it
    * queued for auth, so auth's own copy can be updated with the same version.
    */
  case Updated(version: UUID)

  /** Another user holds the identifier. */
  case AlreadyInUse

  /** Central does not know the user. */
  case UserNotFound

/** Sets a user's email or phone in central, which owns the user index and with it the uniqueness
  * of both across users.
  */
trait UserContactSyncClient:
  def set(userId: UserId, credential: Either[Email, Phone]): Task[UserContactResult]

object UserContactSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, UserContactSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  private case class SetEmailRequest(userId: UserId, email: Email) derives JsonCodec
  private case class SetPhoneRequest(userId: UserId, phone: Phone) derives JsonCodec
  private case class VersionResponse(version: UUID) derives JsonCodec

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends UserContactSyncClient:
    private val EmailUrl = config.central.url / "users" / "email"
    private val PhoneUrl = config.central.url / "users" / "phone"

    override def set(userId: UserId, credential: Either[Email, Phone]): Task[UserContactResult] =
      val (url, body) = credential match
        case Left(email) => (EmailUrl, SetEmailRequest(userId, email).toJson)
        case Right(phone) => (PhoneUrl, SetPhoneRequest(userId, phone).toJson)
      ZIO.scoped:
        for
          response <- centralSyncTokenService.syncRequest(
            Request
              .put(url, Body.fromString(body))
              .addHeader(Header.ContentType(MediaType.application.json)),
          )
          result <- response.status match
            case Status.Conflict => ZIO.succeed(UserContactResult.AlreadyInUse)
            case Status.NotFound => ZIO.succeed(UserContactResult.UserNotFound)
            case status if status.isSuccess =>
              response.body.asJsonFromCodec[VersionResponse].map(r => UserContactResult.Updated(r.version))
            case status => ZIO.fail(new Exception(s"Setting the user contact failed with $status"))
        yield result
