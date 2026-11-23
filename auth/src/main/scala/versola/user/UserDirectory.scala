package versola.user

import versola.oauth.client.CentralSyncTokenService
import versola.oauth.session.model.SupersededSession
import versola.user.model.{Login, UserId}
import versola.util.{CoreConfig, Email, Phone, RoutingKey}
import zio.http.{Body, Header, MediaType, Request, Status}
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

  /** Who holds a credential, and in which cell, without claiming it; `None` when nobody does. Asked by a cell that did
    * not find the credential in its own database, to send the sign-in to the cell that holds the user
    * (versolauth/versola#1046). */
  def lookup(credential: Either[Email, Phone]): Task[Option[UserDirectory.Claim]]

  /** The cell a sign-in by this login is to be sent to, when this cell does not hold the login
    * (versolauth/versola#1048). Central answers a cell for a login nobody holds as well, so that sending a sign-in on
    * does not say that the login exists; `None` only where there is no cell to send it to. */
  def homeOfLogin(login: Login): Task[Option[RoutingKey.CellId]]

  /** The cell that holds a user this cell does not, as a passkey names it; `None` when no cell does. */
  def homeOfUser(userId: UserId): Task[Option[RoutingKey.CellId]]

  /** Has the session a sign-in handed to this cell replaced ended in the cell that holds it (versolauth/versola#1048):
    * central passes it on to the cell of the session's user, which ends it as a logout does. A session that is gone
    * already is not a failure. */
  def sessionSuperseded(session: SupersededSession): Task[Unit]

object UserDirectory:
  /** @param cell the cell that holds the user; `None` on an installation with no cell. */
  final case class Claim(userId: UserId, cell: Option[RoutingKey.CellId])

  /** What central is asked: the credential, and the cell that asks. */
  private[user] case class ClaimRequest(email: Option[Email], phone: Option[Phone], cell: Option[String]) derives JsonCodec

  private[user] case class ClaimResponse(userId: UserId, cell: Option[String]) derives JsonCodec

  private[user] case class HomeRequest(login: Option[Login], userId: Option[UserId]) derives JsonCodec

  private[user] case class HomeResponse(cell: String) derives JsonCodec

  val live: URLayer[CoreConfig & CentralSyncTokenService, UserDirectory] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends UserDirectory:
    private val RegistrationsUrl = config.central.url / "users" / "registrations"

    private val LookupUrl = config.central.url / "users" / "lookup"

    override def lookup(credential: Either[Email, Phone]): Task[Option[Claim]] =
      val request = ClaimRequest(email = credential.left.toOption, phone = credential.toOption, cell = None)
      ZIO.scoped:
        for
          response <- centralSyncTokenService.syncRequest(
            Request
              .post(LookupUrl, Body.fromString(request.toJson))
              .addHeader(Header.ContentType(MediaType.application.json)),
          )
          found <-
            if response.status == Status.NotFound then ZIO.none
            else if !response.status.isSuccess then ZIO.fail(new Exception(s"Looking a credential up failed with ${response.status}"))
            else response.body.asJsonFromCodec[ClaimResponse].flatMap(read).asSome
        yield found

    private val HomeUrl = config.central.url / "users" / "home"

    private val SupersededUrl = config.central.url / "users" / "sessions" / "superseded"

    override def sessionSuperseded(session: SupersededSession): Task[Unit] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(
          Request
            .post(SupersededUrl, Body.fromString(session.toJson))
            .addHeader(Header.ContentType(MediaType.application.json)),
        ).flatMap: response =>
          ZIO.fail(new Exception(s"Ending a superseded session failed with ${response.status}"))
            .unless(response.status.isSuccess || response.status == Status.NotFound)
            .unit

    override def homeOfLogin(login: Login): Task[Option[RoutingKey.CellId]] =
      home(HomeRequest(login = Some(login), userId = None))

    override def homeOfUser(userId: UserId): Task[Option[RoutingKey.CellId]] =
      home(HomeRequest(login = None, userId = Some(userId)))

    private def home(request: HomeRequest): Task[Option[RoutingKey.CellId]] =
      ZIO.scoped:
        for
          response <- centralSyncTokenService.syncRequest(
            Request
              .post(HomeUrl, Body.fromString(request.toJson))
              .addHeader(Header.ContentType(MediaType.application.json)),
          )
          found <-
            if response.status == Status.NotFound then ZIO.none
            else if !response.status.isSuccess then ZIO.fail(new Exception(s"Asking for a home cell failed with ${response.status}"))
            else
              response.body.asJsonFromCodec[HomeResponse].flatMap: answered =>
                ZIO.fromEither(RoutingKey.CellId.parse(answered.cell))
                  .mapBoth(reason => new Exception(s"central answered an invalid cell: $reason"), Some(_))
        yield found

    /** A cell id central answers and this build cannot read is a failure, not "no cell": the user would be created
      * here although it is held elsewhere. */
    private def read(claim: ClaimResponse): Task[Claim] =
      ZIO.foreach(claim.cell)(id =>
        ZIO.fromEither(RoutingKey.CellId.parse(id)).mapError(reason => new Exception(s"central answered an invalid cell: $reason")),
      )
        .map(Claim(claim.userId, _))

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
          claim <- response.body.asJsonFromCodec[ClaimResponse].flatMap(read)
        yield claim
