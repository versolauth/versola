package versola.central.users

import versola.central.antifraud.*
import versola.central.audit.{AuthAuditError, UserAuditEventsQuery, UserAuditEventsResponse}
import versola.central.configuration.clients.ClientId
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.topology.{CellRecord, TopologyRepository}
import versola.util.{Email, PageLimit, Patch, Phone}
import zio.http.URL
import zio.json.ast.Json
import zio.{IO, Task, ZIO}

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
  *  - The antifraud calls go to the configured `auth` only. Each cell keeps its own lists and versioned settings, and
  *    writing one change to several stores that version separately is a decision of its own, not made here.
  *
  * @param at the client of a cell at its internal address; one is kept for each address
  */
final class CellAuthClient(configured: AuthClient, at: URL => AuthClient, users: UserRepository, topology: TopologyRepository) extends AuthClient:
  private val clients = ConcurrentHashMap[String, AuthClient]()

  private def clientOf(cell: CellRecord): Task[AuthClient] =
    ZIO.fromEither(URL.decode(cell.internalUrl))
      .mapBoth(
        error => RuntimeException(s"cell '${cell.id}' has an invalid address: ${error.getMessage}"),
        url => clients.computeIfAbsent(cell.internalUrl, _ => at(url)),
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

  override def createAntifraudListEntry(
      request: NewListEntryRequest,
      caller: AntifraudCaller,
  ): IO[AntifraudListError | Throwable, ListEntryResponse] =
    configured.createAntifraudListEntry(request, caller)

  override def previewAntifraudListEntry(request: NewListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, PreviewResponse] =
    configured.previewAntifraudListEntry(request, caller)

  override def listAntifraudListEntries(
      query: ListEntriesQuery,
      caller: AntifraudCaller,
  ): IO[AntifraudListError | Throwable, List[ListEntryResponse]] =
    configured.listAntifraudListEntries(query, caller)

  override def deleteAntifraudListEntry(request: DeleteListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, Unit] =
    configured.deleteAntifraudListEntry(request, caller)

  override def antifraudListAudit(
      entryId: Option[UUID],
      limit: Option[PageLimit],
      caller: AntifraudCaller,
  ): IO[AntifraudListError | Throwable, List[AuditRecordResponse]] =
    configured.antifraudListAudit(entryId, limit, caller)

  override def putAntifraudSettings(request: PutAntifraudSettingsRequest): IO[AntifraudListError | Throwable, VersionedAntifraudSettings] =
    configured.putAntifraudSettings(request)

  override def patchAntifraudSettings(request: PatchAntifraudSettingsRequest): IO[AntifraudListError | Throwable, VersionedAntifraudSettings] =
    configured.patchAntifraudSettings(request)

  override def deleteAntifraudSettings(
      request: DeleteAntifraudSettingsRequest,
      expectedVersion: Option[Long],
  ): IO[AntifraudListError | Throwable, Unit] =
    configured.deleteAntifraudSettings(request, expectedVersion)

  override def resetAntifraudCounters(request: ResetCountersRequest): IO[AntifraudListError | Throwable, ResetCountersResponse] =
    configured.resetAntifraudCounters(request)

  override def getAntifraudSettings: IO[AntifraudListError | Throwable, VersionedAntifraudSettings] = configured.getAntifraudSettings

  override def antifraudRules: IO[AntifraudListError | Throwable, AntifraudRulesResponse] = configured.antifraudRules

  override def antifraudStatus: IO[AntifraudListError | Throwable, AntifraudStatusResponse] = configured.antifraudStatus

  /** Every cell, and the configured `auth` when it is none of them. One that cannot be reached fails the call, as the
    * one `auth` did: the caller asked for the configuration to be read now. */
  override def syncConfiguration(): Task[Unit] =
    topology.getCells.flatMap(cells => ZIO.foreach(cells)(clientOf)).flatMap: ofCells =>
      val configuredToo = if ofCells.isEmpty then Vector(configured) else ofCells
      ZIO.foreachParDiscard(configuredToo)(_.syncConfiguration())
