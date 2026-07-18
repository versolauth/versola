package versola.configuration.sync

import com.zaxxer.hikari.HikariDataSource
import org.postgresql.PGConnection
import versola.central.configuration.clients.{ClientId, PresetId}
import versola.central.configuration.forms.FormId
import versola.central.configuration.permissions.Permission
import versola.central.configuration.resources.ResourceId
import versola.central.configuration.roles.RoleId
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.sync.{CacheSyncRepository, SyncEvent}
import versola.central.configuration.tenants.TenantId
import zio.json.JsonDecoder
import zio.json.DecoderOps
import zio.*
import zio.stream.{Stream, ZStream}

class PostgresCacheSyncRepository(conn: PGConnection, jdbcConn: java.sql.Connection) extends CacheSyncRepository:

  def getNotifications: Stream[Throwable, SyncEvent] =
    ZStream
      .repeatZIO(
        // Blocks the dedicated LISTEN connection for up to NotificationTimeoutMillis waiting for a
        // notification (pgjdbc's own recommended pattern), instead of busy-polling every 100ms.
        // The underlying blocking socket read does not respond to a plain thread interrupt, so on
        // fiber interruption (e.g. graceful shutdown) we abort the connection instead of closing it:
        // `jdbcConn` is a Hikari-pooled proxy, and Connection#close() on it just returns the
        // (still-blocked-on) connection to the pool for reuse rather than terminating the physical
        // socket — it wouldn't reliably unblock the read, and worse, another caller could borrow the
        // same connection while our read is still in flight on it. Connection#abort(Executor) forcibly
        // terminates the physical connection and evicts it from the pool instead of returning it as
        // healthy, which is exactly what we need here. Runs on the blocking executor since abort() can
        // itself block briefly tearing down the socket.
        ZIO.attemptBlockingCancelable(
          Option(conn.getNotifications(PostgresCacheSyncRepository.NotificationTimeoutMillis))
            .map(_.toList)
            .getOrElse(Nil),
        )(cancel = ZIO.attemptBlocking(jdbcConn.abort(PostgresCacheSyncRepository.directExecutor)).ignore),
      )
      .flattenIterables
      .map(notification => PostgresCacheSyncRepository.parseNotification(notification.getName, notification.getParameter))

object PostgresCacheSyncRepository:
  private val notificationChannels = List(
    "tenant_change",
    "edge_change",
    "jwks_change",
    "client_change",
    "scope_change",
    "role_change",
    "permission_change",
    "resource_change",
    "preset_change",
    "form_change",
    "otp_template_change",
    "challenge_settings_change",
    "theme_change",
    "system_settings_change",
  )

  // pgjdbc's own getNotifications(timeout) example uses 10s; see
  // https://access.crunchydata.com/documentation/pgjdbc/42.1.1/listennotify.html
  private val NotificationTimeoutMillis = 10000

  // Connection#abort(Executor) requires an Executor to run its (usually trivial) teardown task on.
  // This path only runs on interruption/shutdown, so a same-thread executor is sufficient.
  private val directExecutor: java.util.concurrent.Executor = (r: Runnable) => r.run()

  private case class ChangePayload(
      tenantId: Option[String],
      id: String,
      version: Option[Int],
      op: String,
  ) derives JsonDecoder

  private def parsePayload(rawPayload: String): Option[ChangePayload] =
    rawPayload.fromJson[ChangePayload].toOption

  /** Parses a payload and op, falling back to `None` if the JSON is malformed or `op` is not a
    * recognized [[SyncEvent.Op]] value.
    */
  private def parseOpEvent(rawPayload: String)(build: (ChangePayload, SyncEvent.Op) => SyncEvent): SyncEvent =
    (for
      payload <- parsePayload(rawPayload)
      op <- SyncEvent.Op.fromString(payload.op)
    yield build(payload, op)).getOrElse(SyncEvent.Unknown)

  /** As [[parseOpEvent]], additionally requiring a non-empty `tenantId`. */
  private def parseTenantOpEvent(
      rawPayload: String,
  )(build: (ChangePayload, TenantId, SyncEvent.Op) => SyncEvent): SyncEvent =
    parseOpEvent(rawPayload): (payload, op) =>
      payload.tenantId.filter(_.nonEmpty).fold(SyncEvent.Unknown)(tenantId => build(payload, TenantId(tenantId), op))

  /** Parses a raw NOTIFY channel/payload pair into a [[SyncEvent]].
    *
    * Total: malformed JSON, an unrecognized `op`, or a missing/empty `tenantId` (where required)
    * all fall back to [[SyncEvent.Unknown]] instead of throwing.
    */
  private[sync] def parseNotification(channel: String, rawPayload: String): SyncEvent =
    channel match
      case "tenant_change" =>
        SyncEvent.TenantsUpdated

      case "edge_change" =>
        SyncEvent.EdgesUpdated

      case "jwks_change" =>
        SyncEvent.JwksUpdated

      case "client_change" =>
        parseOpEvent(rawPayload): (payload, op) =>
          SyncEvent.ClientsUpdated(id = ClientId(payload.id), op = op)

      case "role_change" =>
        parseTenantOpEvent(rawPayload): (payload, tenantId, op) =>
          SyncEvent.RolesUpdated(tenantId = tenantId, id = RoleId(payload.id), op = op)

      case "scope_change" =>
        parseTenantOpEvent(rawPayload): (payload, tenantId, op) =>
          SyncEvent.ScopesUpdated(tenantId = tenantId, id = ScopeToken(payload.id), op = op)

      case "permission_change" =>
        parseTenantOpEvent(rawPayload): (payload, tenantId, op) =>
          SyncEvent.PermissionsUpdated(tenantId = tenantId, id = Permission(payload.id), op = op)

      case "resource_change" =>
        parseTenantOpEvent(rawPayload): (payload, tenantId, op) =>
          SyncEvent.ResourcesUpdated(tenantId = tenantId, id = ResourceId(payload.id), op = op)

      case "preset_change" =>
        // notify_preset_change() never includes tenantId (authorization_presets has no tenant_id
        // column — presets are scoped via client_id) and PresetsUpdated.matches/sort don't use it,
        // so unlike the other tenant-scoped channels, tenantId is not required here.
        parseOpEvent(rawPayload): (payload, op) =>
          SyncEvent.PresetsUpdated(tenantId = TenantId(""), id = PresetId(payload.id), op = op)

      case "form_change" =>
        parseOpEvent(rawPayload): (payload, op) =>
          SyncEvent.FormsUpdated(id = FormId(payload.id), version = payload.version.getOrElse(0), op = op)

      case "otp_template_change" =>
        parseTenantOpEvent(rawPayload): (payload, tenantId, op) =>
          SyncEvent.OtpTemplatesUpdated(tenantId = tenantId, id = payload.id, op = op)

      case "challenge_settings_change" =>
        parseTenantOpEvent(rawPayload): (_, tenantId, op) =>
          SyncEvent.ChallengeSettingsUpdated(tenantId = tenantId, op = op)

      case "theme_change" =>
        SyncEvent.ThemesUpdated

      case "system_settings_change" =>
        SyncEvent.SystemSettingsUpdated

      case _ =>
        SyncEvent.Unknown

  def live: ZLayer[HikariDataSource & Scope, Throwable, CacheSyncRepository] =
    ZLayer:
      for
        ds <- ZIO.service[HikariDataSource]
        jdbcConn <- ZIO.acquireRelease(ZIO.attempt(ds.getConnection()))(c => ZIO.attempt(c.close()).orDie)
        _ <- ZIO.attempt {
          val statement = jdbcConn.createStatement()
          try notificationChannels.foreach(channel => statement.execute(s"LISTEN $channel"))
          finally statement.close()
        }
        conn <- ZIO.attempt(jdbcConn.unwrap(classOf[PGConnection]))
      yield PostgresCacheSyncRepository(conn, jdbcConn)
