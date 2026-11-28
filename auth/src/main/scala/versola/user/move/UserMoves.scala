package versola.user.move

import versola.audit.AuditSupport.*
import versola.audit.{AuthActors, AuthAuditDetails, AuthAuditKinds}
import versola.oauth.AuthMetrics
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, TenantId}
import versola.oauth.handoff.CellEnvelope
import versola.user.UserRepository
import versola.user.model.UserId
import versola.user.move.UserMoveRepository.{Completed, Written}
import versola.util.audit.{AuditDetails, AuditDraft, AuditEvents, AuditKind, AuditOutcome}
import versola.util.http.Observability
import versola.util.{CoreConfig, RoutingKey}
import zio.{Clock, IO, Schedule, Scope, Task, ZIO, ZLayer, durationInt}

import java.time.Duration as JDuration
import java.util.UUID

/** Moving a user between cells, as the two cells see it (versolauth/versola#1051). Central drives the move; every step is
  * idempotent, so that a step whose answer was lost is repeated.
  *
  * The old cell: [[begin]] marks the user `leaving`, [[exportUser]] hands the rows over sealed for the new cell, [[complete]]
  * deletes them and leaves the user `moved`, [[cancel]] abandons the move. The new cell: [[arrive]] takes the rows in (and again,
  * when the source changed), [[keep]] keeps them once the directory names this cell, [[forgetArrival]] removes what an abandoned
  * move left. Every step carries the id of the move, and an arrival is bound to it.
  */
trait UserMoves:
  /** The user is `leaving`, to `to`, for the move `moveId`. A repeat for the same cell and move is accepted. */
  def begin(userId: UserId, to: String, moveId: UUID): IO[Throwable | MoveError, Unit]

  /** The user's rows, sealed for the cell the departure names, and the digest of the rows. Only for a `leaving` user. */
  def exportUser(userId: UserId): IO[Throwable | MoveError, UserMoves.Exported]

  /** The departure of the move becomes `moved` and the user's rows are deleted; `digest` is the one of the last export, and
    * when the rows are no longer what it says the move is still completed (it is past its flip) and the loss is reported. A repeat
    * is accepted. */
  def complete(userId: UserId, moveId: UUID, digest: String): IO[Throwable | MoveError, Unit]

  /** The move was abandoned: the user is served again. Nothing to do for a user that is not leaving; a `moved` one is refused. */
  def cancel(userId: UserId): IO[Throwable | MoveError, Unit]

  def summary: Task[DepartureSummary]

  /** Opens the envelope, which must have been sealed by `from` for this cell, for this user and this move, checks the document
    * against the configuration of this cell, and takes the rows in; once more for the same move, replaces them. */
  def arrive(envelope: String, userId: UserId, from: String, moveId: UUID): IO[Throwable | MoveError, Unit]

  /** The arrival of the move is kept: central flipped the directory. Refused when this cell has no arrival of that move. */
  def keep(userId: UserId, moveId: UUID): IO[Throwable | MoveError, Unit]

  /** Removes what an abandoned move left here, when the arrival of that move is still pending; nothing otherwise. */
  def forgetArrival(userId: UserId, moveId: UUID): IO[Throwable, Unit]

/** Why a step of a move is refused; a closed set, `code` is the recorded error and what central reads. */
enum MoveError(val code: String):
  /** An auth that is no cell has nobody to move to or from. */
  case NotACell extends MoveError("not_a_cell")

  /** `to` is not a cell id, or is this cell. */
  case InvalidCell extends MoveError("invalid_cell")

  case UserNotFound extends MoveError("user_not_found")

  /** The user has a departure of another move or to another cell already. */
  case DepartureExists extends MoveError("departure_exists")

  /** The user is not `leaving` (for that move). */
  case NotLeaving extends MoveError("not_leaving")

  /** The user has moved already, so the move cannot be abandoned. */
  case UserMoved extends MoveError("user_moved")

  /** This cell has no key to seal for the cell the user is going to (yet): the same step is tried again. */
  case NoCellKey extends MoveError("no_cell_key")

  /** The user is here, or was brought by a move that is done, or an identifier, a passkey or a token is held by someone else. */
  case UserExists extends MoveError("user_exists")

  /** This cell has no arrival of that move to keep. */
  case NoArrival extends MoveError("no_arrival")

  /** The envelope was sealed by another cell than the one central named. */
  case EnvelopeOtherCell extends MoveError("envelope_other_cell")

  /** The document is of another user than the one central named. */
  case EnvelopeOtherUser extends MoveError("envelope_other_user")

  /** The document was made for another move than the one central named. */
  case EnvelopeOtherMove extends MoveError("envelope_other_move")

  /** The document, or the request that carries it, is over what an arrival takes. */
  case TooLarge extends MoveError("too_large")

  case Envelope(refused: CellEnvelope.Refused) extends MoveError(s"envelope_${refused.code}")
  case Document(error: UserMoveDocument.DocumentError) extends MoveError(error.code)

object UserMoves:
  /** What an export answers: the sealed rows, and the digest of the rows that were sealed. */
  final case class Exported(envelope: String, digest: String)

  /** How often the tombstones of expired refresh tokens and the stale arrivals are removed. */
  val CleanupEvery = 1.hour

  /** How long an arrival is remembered after its user is gone: past an envelope's life the move id cannot be replayed with it. */
  val ArrivalMemory: JDuration = JDuration.ofHours(24)

  /** The most a document of an arrival may weigh, in bytes, before it is parsed. */
  val MaxDocumentBytes = 1024 * 1024

  val live: ZLayer[
    CoreConfig & UserRepository & UserDepartureRepository & UserMoveRepository & CellEnvelope & AuditEvents & OAuthConfigurationService & Scope,
    Nothing,
    UserMoves,
  ] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[CoreConfig]
        users <- ZIO.service[UserRepository]
        departures <- ZIO.service[UserDepartureRepository]
        moves <- ZIO.service[UserMoveRepository]
        envelope <- ZIO.service[CellEnvelope]
        audit <- ZIO.service[AuditEvents]
        configuration <- ZIO.service[OAuthConfigurationService]
        // Rows past their expiry answer nothing, and are removed in the background. A failed run is tried again at the next.
        _ <- (departures.removeExpiredTokens *>
          Clock.instant.flatMap(now => moves.removeStaleArrivals(now.minus(ArrivalMemory))))
          .catchAllCause(cause =>
            ZIO.logWarningCause("Could not remove the expired tombstones of moved refresh tokens or the stale arrivals; tried again later", cause),
          )
          .repeat(Schedule.spaced(CleanupEvery))
          .forkScoped
      yield Impl(config, users, departures, moves, envelope, audit, configuration)

  def make(
      config: CoreConfig,
      users: UserRepository,
      departures: UserDepartureRepository,
      moves: UserMoveRepository,
      envelope: CellEnvelope,
      audit: AuditEvents,
      configuration: OAuthConfigurationService,
  ): UserMoves = Impl(config, users, departures, moves, envelope, audit, configuration)

  private class Impl(
      config: CoreConfig,
      users: UserRepository,
      departures: UserDepartureRepository,
      moves: UserMoveRepository,
      envelope: CellEnvelope,
      audit: AuditEvents,
      configuration: OAuthConfigurationService,
  ) extends UserMoves:

    private def refuse(error: MoveError, description: Option[String] = None): ZIO[Any, MoveError, Nothing] =
      Observability.setError(error.code, description) *> ZIO.fail(error)

    private val central = Some((AuthActors.service, Some("central")))

    private def audited(kind: AuditKind, userId: UserId, details: AuditDetails): IO[Throwable, Unit] =
      audit.log(
        AuditDraft(
          kind = kind,
          outcome = AuditOutcome.success,
          reason = None,
          actor = central,
          userId = Some(userId),
          target = Some(("user", userId.toString)),
          details = details,
        ),
      )

    override def begin(userId: UserId, to: String, moveId: UUID): IO[Throwable | MoveError, Unit] =
      for
        _ <- Observability.setUserId(userId.toString)
        own <- ZIO.fromOption(config.cellId).orElse(refuse(MoveError.NotACell))
        target <- ZIO.fromEither(RoutingKey.CellId.parse(to)).orElse(refuse(MoveError.InvalidCell))
        _ <- refuse(MoveError.InvalidCell).when(target == own)
        existing <- departures.find(userId)
        _ <- existing match
          // A repeat of a step whose answer was lost.
          case Some(departure) if departure.toCell == target && departure.moveId == moveId => ZIO.unit
          case Some(_) => refuse(MoveError.DepartureExists)
          case None =>
            users.find(userId).flatMap:
              case None => refuse(MoveError.UserNotFound)
              case Some(_) => Clock.instant.flatMap(departures.begin(userId, target, moveId, _)).unit
      yield ()

    override def exportUser(userId: UserId): IO[Throwable | MoveError, Exported] =
      for
        _ <- Observability.setUserId(userId.toString)
        departure <- departures.find(userId).someOrElseZIO(refuse(MoveError.NotLeaving))
        _ <- refuse(MoveError.NotLeaving).unless(departure.state == DepartureState.Leaving)
        to <- ZIO.fromEither(RoutingKey.CellId.parse(departure.toCell)).orElse(refuse(MoveError.InvalidCell))
        document <- moves.read(userId, departure.moveId).someOrElseZIO(refuse(MoveError.UserNotFound))
        digest <- ZIO.attempt(UserMoveDocument.digest(document))
        text <- envelope.seal(to, CellEnvelope.Purpose.UserMove, UserMoveDocument.encode(document)).catchSome:
          case CellEnvelope.NoKey(_) => refuse(MoveError.NoCellKey)
          case CellEnvelope.NotACell => refuse(MoveError.NotACell)
      yield Exported(envelope = text, digest = digest)

    override def complete(userId: UserId, moveId: UUID, digest: String): IO[Throwable | MoveError, Unit] =
      for
        _ <- Observability.setUserId(userId.toString)
        result <- moves.complete(userId, moveId, digest)
        _ <- result match
          case Completed.Moved(toCell, _, changed) =>
            audited(AuthAuditKinds.UserMovedOut, userId, AuthAuditDetails.UserMovedOut(cell = toCell, changedAfterExport = changed)) *>
              AuthMetrics.movedOutChanged.when(changed)
          case Completed.AlreadyMoved(_) => ZIO.unit
          case Completed.NotLeaving => refuse(MoveError.NotLeaving)
      yield ()

    override def cancel(userId: UserId): IO[Throwable | MoveError, Unit] =
      for
        _ <- Observability.setUserId(userId.toString)
        removed <- departures.cancel(userId)
        _ <-
          if removed then ZIO.unit
          else
            departures.find(userId).flatMap:
              case Some(_) => refuse(MoveError.UserMoved)
              case None => ZIO.unit
      yield ()

    override def summary: Task[DepartureSummary] = departures.summary

    /** Every tenant, client and scope the rows name is one this cell knows. Roles: this cell is not told which roles a tenant has
      * (central keeps them), so what can be checked is the tenant. */
    private def inConfiguration(document: UserMoveDocument): IO[Throwable | MoveError, Unit] =
      def unknown(what: String) = refuse(MoveError.Document(UserMoveDocument.DocumentError.NotInConfiguration(what)), Some(what))
      for
        tenants <- ZIO.foreach(document.roles.map(_.tenantId).distinct): tenant =>
          if tenant == (TenantId.default: String) then ZIO.succeed(true)
          else configuration.findByTenant(TenantId(tenant)).map(_.nonEmpty)
        _ <- unknown("role_tenant").when(tenants.contains(false))
        clientIds = (document.consents.map(_.clientId) ++ document.refreshTokens.map(_.clientId)).distinct
        clients <- ZIO.foreach(clientIds)(id => configuration.find(ClientId(id)).map(id -> _)).map(_.toMap)
        _ <- unknown("consent_client").when(document.consents.exists(consent => clients.get(consent.clientId).flatten.isEmpty))
        _ <- unknown("token_client").when(document.refreshTokens.exists(token => clients.get(token.clientId).flatten.isEmpty))
        _ <- unknown("token_scope").when:
          document.refreshTokens.exists: token =>
            clients.get(token.clientId).flatten.exists(client =>
              !token.scope.forall(scope => client.scope.exists(allowed => (allowed: String) == scope)),
            )
      yield ()

    override def arrive(presented: String, userId: UserId, from: String, moveId: UUID): IO[Throwable | MoveError, Unit] =
      for
        _ <- Observability.setUserId(userId.toString)
        opened <- envelope.open(CellEnvelope.Purpose.UserMove, presented).catchAll(refused => refuse(MoveError.Envelope(refused)))
        (sealer, bytes) = opened
        // The cell that made it is the one central says, and what it holds is for this user and this move: an envelope of
        // another move, replayed or swapped, is not taken.
        _ <- refuse(MoveError.EnvelopeOtherCell).unless(sealer == from)
        _ <- refuse(MoveError.TooLarge).when(bytes.length > MaxDocumentBytes)
        document <- ZIO.fromEither(UserMoveDocument.decode(bytes)).catchAll(error => refuse(MoveError.Document(error)))
        _ <- refuse(MoveError.EnvelopeOtherUser).unless(document.userId == (userId: UUID))
        _ <- refuse(MoveError.EnvelopeOtherMove).unless(document.moveId == moveId)
        _ <- inConfiguration(document)
        now <- Clock.instant
        written <- moves.write(document, sealer, now)
        _ <- written match
          case Written.Inserted => audited(AuthAuditKinds.UserMovedIn, userId, AuthAuditDetails.UserMoved(cell = sealer))
          case Written.Replaced => ZIO.unit
          case Written.Taken => refuse(MoveError.UserExists)
      yield ()

    override def keep(userId: UserId, moveId: UUID): IO[Throwable | MoveError, Unit] =
      Observability.setUserId(userId.toString) *>
        Clock.instant.flatMap(moves.keep(userId, moveId, _)).flatMap:
          case true => ZIO.unit
          case false => refuse(MoveError.NoArrival)

    override def forgetArrival(userId: UserId, moveId: UUID): IO[Throwable, Unit] =
      Observability.setUserId(userId.toString) *>
        moves.discard(userId, moveId).flatMap:
          case Some(from) => audited(AuthAuditKinds.UserArrivalDiscarded, userId, AuthAuditDetails.UserMoved(cell = from))
          case None => ZIO.unit
