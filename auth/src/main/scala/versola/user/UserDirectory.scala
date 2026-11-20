package versola.user

import versola.oauth.client.CentralSyncTokenService
import versola.user.model.UserId
import versola.util.{CoreConfig, Email, Phone, RoutingKey}
import zio.http.{Body, Header, MediaType, Request}
import zio.json.*
import zio.{Task, URLayer, ZIO, ZLayer}

/** The directory of the partition (versolauth/versola#1041): which user an identifier belongs to and which cell holds
  * that user. Each cell has its own database, so the uniqueness of a phone or an email across cells is decided there
  * and not by an index here.
  *
  * A trait in front of where the directory is: today central's API, behind which it is two tables of central's
  * database.
  */
trait UserDirectory:
  /** Claims a credential that was just proven (a confirmed code) for the user this cell is about to create, and
    * answers who holds it: a new id and this cell when nobody did, the owner and the owner's cell otherwise. A claim is
    * permanent and repeating it answers the same, so a registration that stopped after the claim is finished by the
    * next one.
    */
  def claim(credential: Either[Email, Phone]): Task[UserDirectory.Claim]

object UserDirectory:
  /** @param cell the cell that holds the user; `None` on an installation with no cell. */
  final case class Claim(userId: UserId, cell: Option[RoutingKey.CellId])

  /** What central is asked: the credential, and the cell that asks. */
  private[user] case class ClaimRequest(email: Option[Email], phone: Option[Phone], cell: Option[String]) derives JsonCodec

  private[user] case class ClaimResponse(userId: UserId, cell: Option[String]) derives JsonCodec

  val live: URLayer[CoreConfig & CentralSyncTokenService, UserDirectory] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends UserDirectory:
    private val RegistrationsUrl = config.central.url / "users" / "registrations"

    override def claim(credential: Either[Email, Phone]): Task[Claim] =
      val request = ClaimRequest(email = credential.left.toOption, phone = credential.toOption, cell = config.cellId.map(identity[String]))
      ZIO.scoped:
        for
          response <- centralSyncTokenService.syncRequest(
            Request
              .post(RegistrationsUrl, Body.fromString(request.toJson))
              .addHeader(Header.ContentType(MediaType.application.json)),
          )
          _ <- ZIO.unless(response.status.isSuccess):
            ZIO.fail(new Exception(s"Claiming registration failed with ${response.status}"))
          claim <- response.body.asJsonFromCodec[ClaimResponse]
          // A cell id central answers and this build cannot read is a failure, not "no cell": the user would be
          // created here although it is held elsewhere.
          cell <- ZIO.foreach(claim.cell)(id =>
            ZIO.fromEither(RoutingKey.CellId.parse(id)).mapError(reason => new Exception(s"central answered an invalid cell: $reason")),
          )
        yield Claim(claim.userId, cell)
