package versola.oauth.identifier

import versola.oauth.client.CentralSyncTokenService
import versola.user.given
import versola.user.model.UserId
import versola.util.{CoreConfig, Email, Phone}
import zio.http.{Body, Header, MediaType, Request, Status}
import zio.json.*
import zio.{Task, URLayer, ZIO, ZLayer}

import java.util.UUID

enum IdentifierChangeResult:
  /** Central updated its user index; `version` orders the change against the outbox events it
    * queued for auth, so auth's own copy can be updated with the same version.
    */
  case Changed(version: UUID)

  /** Another user holds the identifier. */
  case AlreadyInUse

  /** Central does not know the user. */
  case UserNotFound

/** Moves a user's email or phone in central, which owns the user index and with it the uniqueness
  * of identifiers across tenants' users.
  */
trait IdentifierChangeSyncClient:
  def change(userId: UserId, credential: Either[Email, Phone]): Task[IdentifierChangeResult]

object IdentifierChangeSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, IdentifierChangeSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  private case class ChangeRequest(userId: UserId, email: Option[Email], phone: Option[Phone]) derives JsonCodec
  private case class ChangeResponse(version: UUID) derives JsonCodec

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends IdentifierChangeSyncClient:
    private val ChangesUrl = config.central.url / "users" / "identifier-changes"

    override def change(userId: UserId, credential: Either[Email, Phone]): Task[IdentifierChangeResult] =
      val body = credential match
        case Left(email) => ChangeRequest(userId, Some(email), None)
        case Right(phone) => ChangeRequest(userId, None, Some(phone))
      ZIO.scoped:
        for
          response <- centralSyncTokenService.syncRequest(
            Request
              .post(ChangesUrl, Body.fromString(body.toJson))
              .addHeader(Header.ContentType(MediaType.application.json)),
          )
          result <- response.status match
            case Status.Conflict => ZIO.succeed(IdentifierChangeResult.AlreadyInUse)
            case Status.NotFound => ZIO.succeed(IdentifierChangeResult.UserNotFound)
            case status if status.isSuccess =>
              response.body.asJsonFromCodec[ChangeResponse].map(r => IdentifierChangeResult.Changed(r.version))
            case status => ZIO.fail(new Exception(s"Changing the identifier failed with $status"))
        yield result
