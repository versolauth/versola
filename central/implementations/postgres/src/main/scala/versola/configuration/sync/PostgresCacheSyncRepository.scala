package versola.configuration.sync

import com.zaxxer.hikari.HikariDataSource
import versola.central.configuration.clients.{ClientId, PresetId}
import versola.central.configuration.details.AuthorizationDetailType
import versola.central.configuration.forms.FormId
import versola.central.configuration.permissions.Permission
import versola.central.configuration.resources.ResourceId
import versola.central.configuration.roles.RoleId
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.challenges.{OtpTemplateChannel, OtpTemplatePurpose}
import versola.central.configuration.sync.{CacheSyncRepository, SyncEvent}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.PostgresNotificationListener
import zio.json.JsonDecoder
import zio.json.DecoderOps
import zio.*
import zio.stream.Stream

class PostgresCacheSyncRepository(listener: PostgresNotificationListener) extends CacheSyncRepository:

  def getNotifications: Stream[Throwable, SyncEvent] =
    listener.notifications
      .map(notification => PostgresCacheSyncRepository.parseNotification(notification.getName, notification.getParameter))

object PostgresCacheSyncRepository:
  private val notificationChannels = List(
    "tenant_change",
    "edge_change",
    "jwks_change",
    "client_change",
    "scope_change",
    "authorization_detail_type_change",
    "role_change",
    "permission_change",
    "resource_change",
    "preset_change",
    "form_change",
    "otp_template_change",
    "challenge_settings_change",
    "theme_change",
    "system_settings_change",
    "metadata_change",
  )

  private case class ChangePayload(
      tenantId: Option[String],
      id: String,
      purpose: Option[String],
      channel: Option[String],
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

      case "authorization_detail_type_change" =>
        parseTenantOpEvent(rawPayload): (payload, tenantId, op) =>
          SyncEvent.AuthorizationDetailTypesUpdated(
            tenantId = tenantId,
            id = AuthorizationDetailType(payload.id),
            op = op,
          )

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
          (for
            purpose <- payload.purpose.flatMap(value => OtpTemplatePurpose.values.find(_.toString == value))
            channel <- payload.channel.flatMap(value => OtpTemplateChannel.values.find(_.toString == value))
          yield SyncEvent.OtpTemplatesUpdated(tenantId = tenantId, id = payload.id, purpose = purpose, channel = channel, op = op))
            .getOrElse(SyncEvent.Unknown)

      case "challenge_settings_change" =>
        parseTenantOpEvent(rawPayload): (_, tenantId, op) =>
          SyncEvent.ChallengeSettingsUpdated(tenantId = tenantId, op = op)

      case "theme_change" =>
        SyncEvent.ThemesUpdated

      case "system_settings_change" =>
        SyncEvent.SystemSettingsUpdated

      case "metadata_change" =>
        SyncEvent.ServerMetadataUpdated

      case _ =>
        SyncEvent.Unknown

  def live: ZLayer[HikariDataSource & Scope, Throwable, CacheSyncRepository] =
    ZLayer:
      PostgresNotificationListener.make(notificationChannels).map(PostgresCacheSyncRepository(_))
