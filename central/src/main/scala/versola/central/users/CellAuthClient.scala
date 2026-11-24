package versola.central.users

import versola.central.antifraud.*
import versola.central.audit.{AuthAuditError, UserAuditEventsQuery, UserAuditEventsResponse}
import versola.central.configuration.clients.ClientId
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.topology.{CellId, CellRecord, TopologyRepository}
import versola.util.{Email, Patch, Phone}
import zio.http.URL
import zio.json.ast.Json
import zio.{Cause, IO, Task, ZIO}

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Central's calls to `auth` once there are several of them (versolauth/versola#547, Phase 2 step 3): a call about a user
  * goes to the cell that holds the user, which central's own directory says (versolauth/versola#1041), so no cell is
  * asked where the user is and none answers "elsewhere".
  *
  *  - A user of no cell, a user whose cell is not registered (an `auth` may name itself a cell before its cell is
  *    registered), and a user the directory does not have go to the `auth` central is configured with, as every call did.
  *  - The nudge to read the configuration again goes to every cell: each has its own caches.
  *  - The antifraud policy is central's own and no cell is written to. A check of an entry or of the settings is
  *    answered by the configured `auth`; what an entry matched, how often it fired and a reset of the counters are each
  *    cell's own and go to every cell. The rule names and the status are those of the configured `auth`.
  *
  * @param at the client of a cell at its internal address, which sends the cell, and the cell only, a token made for it
  *   (the cell's id is the audience, #1050); one is kept for each cell and address
  */
final class CellAuthClient(configured: AuthClient, at: (URL, CellId) => AuthClient, users: UserRepository, topology: TopologyRepository)
  extends AuthClient:
  private val clients = ConcurrentHashMap[(CellId, String), AuthClient]()

  private def clientOf(cell: CellRecord): Task[AuthClient] =
    ZIO.fromEither(URL.decode(cell.internalUrl))
      .mapBoth(
        error => RuntimeException(s"cell '${cell.id}' has an invalid address: ${error.getMessage}"),
        url => clients.computeIfAbsent((cell.id, cell.internalUrl), _ => at(url, cell.id)),
      )

  /** The `auth` that holds the user. */
  private def of(id: UserId): Task[AuthClient] =
    users.find(id).map(_.flatMap(_.cellId)).flatMap:
      case None => ZIO.succeed(configured)
      case Some(cell) => topology.getCells.map(_.find(_.id == cell)).flatMap(_.fold(ZIO.succeed(configured))(clientOf))

  override def upsertUser(id: UserId, version: UUID, email: Option[Email], phone: Option[Phone], login: Option[Login]): Task[Unit] =
    of(id).flatMap(_.upsertUser(id, version, email, phone, login))

  override def patchUser(
      id: UserId,
      version: UUID,
      email: Option[Patch[Email]],
      phone: Option[Patch[Phone]],
      login: Option[Patch[Login]],
  ): Task[Unit] =
    of(id).flatMap(_.patchUser(id, version, email, phone, login))

  override def updateUserRoles(userId: UserId, tenantId: TenantId, add: Set[RoleId], remove: Set[RoleId]): Task[Unit] =
    of(userId).flatMap(_.updateUserRoles(userId, tenantId, add, remove))

  override def deleteUser(userId: UserId): Task[Unit] =
    of(userId).flatMap(_.deleteUser(userId))

  override def deleteUserOf(cell: Option[CellId], userId: UserId): Task[Unit] =
    cell match
      case None => configured.deleteUser(userId)
      case Some(id) =>
        topology.getCells.map(_.find(_.id == id)).flatMap:
          case None => configured.deleteUser(userId)
          case Some(registered) => clientOf(registered).flatMap(_.deleteUser(userId))

  override def getUser(id: UserId): Task[Option[AuthClient.UserDto]] =
    of(id).flatMap(_.getUser(id))

  override def getUserClaims(id: UserId): Task[Option[Json.Obj]] =
    of(id).flatMap(_.getUserClaims(id))

  override def patchUserClaims(id: UserId, patch: Json.Obj): Task[Unit] =
    of(id).flatMap(_.patchUserClaims(id, patch))

  override def getUserRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]] =
    of(id).flatMap(_.getUserRoles(id, tenantId))

  override def getUserSessions(id: UserId): Task[List[AuthClient.SessionDto]] =
    of(id).flatMap(_.getUserSessions(id))

  override def getUserDevices(id: UserId): Task[List[AuthClient.DeviceDto]] =
    of(id).flatMap(_.getUserDevices(id))

  override def forgetUserAgent(userId: UserId, userAgentId: String): Task[Boolean] =
    of(userId).flatMap(_.forgetUserAgent(userId, userAgentId))

  override def invalidateSession(userId: UserId): Task[Unit] =
    of(userId).flatMap(_.invalidateSession(userId))

  override def endSession(userId: UserId, sessionId: String): Task[Boolean] =
    of(userId).flatMap(_.endSession(userId, sessionId))

  override def supersedeSession(userId: UserId, sessionId: String): Task[Boolean] =
    of(userId).flatMap(_.supersedeSession(userId, sessionId))

  override def revokeRefreshTokens(userId: UserId, clientId: ClientId): Task[Unit] =
    of(userId).flatMap(_.revokeRefreshTokens(userId, clientId))

  override def getUserRefreshTokens(id: UserId): Task[List[AuthClient.RefreshTokenDto]] =
    of(id).flatMap(_.getUserRefreshTokens(id))

  override def resetUserLimits(userId: UserId, tenantId: TenantId, email: Option[Email], phone: Option[Phone]): Task[Unit] =
    of(userId).flatMap(_.resetUserLimits(userId, tenantId, email, phone))

  override def listPasskeys(userId: UserId): Task[List[PasskeyInfo]] =
    of(userId).flatMap(_.listPasskeys(userId))

  override def renamePasskey(userId: UserId, credentialId: String, name: Option[String]): Task[Unit] =
    of(userId).flatMap(_.renamePasskey(userId, credentialId, name))

  override def deletePasskey(userId: UserId, credentialId: String): Task[Unit] =
    of(userId).flatMap(_.deletePasskey(userId, credentialId))

  override def resetPassword(userId: UserId, expiresInSeconds: Long, channel: Option[DeliveryChannel]): Task[Option[String]] =
    of(userId).flatMap(_.resetPassword(userId, expiresInSeconds, channel))

  override def setPassword(userId: UserId, password: String): Task[Unit] =
    of(userId).flatMap(_.setPassword(userId, password))

  override def getUserAuditEvents(userId: UserId, query: UserAuditEventsQuery): IO[AuthAuditError | Throwable, UserAuditEventsResponse] =
    of(userId).flatMap(_.getUserAuditEvents(userId, query))

  override def getUserAuditSummary(userId: UserId, from: Option[Instant], to: Option[Instant]): IO[AuthAuditError | Throwable, Json.Obj] =
    of(userId).flatMap(_.getUserAuditSummary(userId, from, to))

  override def checkAntifraudListEntry(entry: ListEntryCheck): IO[AntifraudListError | Throwable, CheckedListEntry] =
    configured.checkAntifraudListEntry(entry)

  /** Every cell is asked, and what the entry matched is the sum. They agree on the value (it is normalised by the same
    * rule, and a phone is hashed with the key they share), so the first one's is answered. */
  override def previewAntifraudListEntry(entry: ListEntryCheck): IO[AntifraudListError | Throwable, CheckedListEntry] =
    every.flatMap(clients => ZIO.foreachPar(clients)(_.previewAntifraudListEntry(entry))).map: answers =>
      val previews = answers.flatMap(_.preview)
      CheckedListEntry(
        value = answers.head.value,
        preview = Option.when(previews.nonEmpty):
          PreviewResponse(
            // A kind of value the event log has no column for is counted by no cell.
            matched = Option.when(previews.exists(_.matched.isDefined))(previews.flatMap(_.matched).sum),
            total = previews.map(_.total).sum,
          ),
      )

  override def antifraudListHits(ids: List[UUID]): Task[List[ListHits]] =
    every.flatMap: clients =>
      ZIO.foreachPar(clients): client =>
        client.antifraudListHits(ids).catchAll: error =>
          ZIO.logWarningCause("a cell did not answer how often the antifraud list entries matched; its hits are left out", Cause.fail(error)).as(Nil)
    .map: answers =>
      answers.flatten.groupMapReduce(_.id)(identity)((a, b) =>
        ListHits(id = a.id, count = a.count + b.count, lastAt = if a.lastAt.isAfter(b.lastAt) then a.lastAt else b.lastAt),
      ).values.toList

  override def checkAntifraudSettings(check: SettingsCheck): IO[AntifraudListError | Throwable, AntifraudSettings] =
    configured.checkAntifraudSettings(check)

  /** Every cell clears its own counters. One that cannot be reached fails the call: an operator who cleared a number
    * must not be told it is done while a cell still refuses it. */
  override def resetAntifraudCounters(request: ResetCountersRequest): IO[AntifraudListError | Throwable, ResetCountersResponse] =
    every.flatMap(clients => ZIO.foreachPar(clients)(_.resetAntifraudCounters(request))).map: answers =>
      ResetCountersResponse(answers.map(_.generation).max)

  override def antifraudRules: IO[AntifraudListError | Throwable, AntifraudRulesResponse] = configured.antifraudRules

  override def antifraudStatus: IO[AntifraudListError | Throwable, AntifraudInstanceStatus] = configured.antifraudStatus

  override def antifraudStatusOfCells: Task[List[AntifraudCellStatus]] =
    topology.getCells.flatMap: cells =>
      ZIO.foreachPar(cells.toList): cell =>
        clientOf(cell).flatMap(_.antifraudStatus).fold(
          {
            case error: Throwable => AntifraudCellStatus(cell = cell.id, status = None, error = Some(reasonOf(error)))
            case refusal: AntifraudListError => AntifraudCellStatus(cell = cell.id, status = None, error = Some(refusal.toString))
          },
          status => AntifraudCellStatus(cell = cell.id, status = Some(status), error = None),
        )

  /** Short enough to show, and never the whole of what a failed call carried. */
  private def reasonOf(error: Throwable): String =
    Option(error.getMessage).getOrElse(error.getClass.getSimpleName).take(200)

  /** Every cell, and the configured `auth` when there is none. */
  private def every: Task[Vector[AuthClient]] =
    topology.getCells.flatMap(cells => ZIO.foreach(cells)(clientOf)).map(ofCells => if ofCells.isEmpty then Vector(configured) else ofCells)

  /** Every cell, and the configured `auth` when it is none of them. One that cannot be reached fails the call, as the
    * one `auth` did: the caller asked for the configuration to be read now. */
  override def syncConfiguration(): Task[Unit] =
    every.flatMap(clients => ZIO.foreachParDiscard(clients)(_.syncConfiguration()))
