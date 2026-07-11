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

class PostgresCacheSyncRepository(conn: PGConnection) extends CacheSyncRepository:

  private case class ChangePayload(
      tenantId: Option[String],
      id: String,
      version: Option[Int],
      op: String,
  ) derives JsonDecoder

  private def parsePayload(parameter: String): Option[ChangePayload] =
    parameter.fromJson[ChangePayload].toOption

  def getNotifications: Stream[Throwable, SyncEvent] =
    ZStream
      .repeatZIO(
        ZIO.attemptBlocking(Option(conn.getNotifications()).map(_.toList).getOrElse(Nil)) <* ZIO.sleep(100.millis),
      )
      .flattenIterables
      .map { notification =>
        notification.getName match
          case "tenant_change" =>
            SyncEvent.TenantsUpdated

          case "edge_change" =>
            SyncEvent.EdgesUpdated

          case "jwks_change" =>
            SyncEvent.JwksUpdated

          case "client_change" =>
            parsePayload(notification.getParameter).fold[SyncEvent](SyncEvent.Unknown) { payload =>
              SyncEvent.ClientsUpdated(
                id = ClientId(payload.id),
                op = SyncEvent.Op.valueOf(payload.op),
              )
            }
          case "role_change" =>
            parsePayload(notification.getParameter).fold[SyncEvent](SyncEvent.Unknown) { payload =>
              SyncEvent.RolesUpdated(
                tenantId = TenantId(payload.tenantId.getOrElse("")),
                id = RoleId(payload.id),
                op = SyncEvent.Op.valueOf(payload.op),
              )
            }
          case "scope_change" =>
            parsePayload(notification.getParameter).fold[SyncEvent](SyncEvent.Unknown) { payload =>
              SyncEvent.ScopesUpdated(
                tenantId = TenantId(payload.tenantId.getOrElse("")),
                id = ScopeToken(payload.id),
                op = SyncEvent.Op.valueOf(payload.op),
              )
            }
          case "permission_change" =>
            parsePayload(notification.getParameter).fold[SyncEvent](SyncEvent.Unknown) { payload =>
              payload.tenantId.filter(_.nonEmpty).fold[SyncEvent](SyncEvent.Unknown) { tenantId =>
                SyncEvent.PermissionsUpdated(
                  tenantId = TenantId(tenantId),
                  id = Permission(payload.id),
                  op = SyncEvent.Op.valueOf(payload.op),
                )
              }
            }
          case "resource_change" =>
            parsePayload(notification.getParameter).fold[SyncEvent](SyncEvent.Unknown) { payload =>
              payload.tenantId.fold[SyncEvent](SyncEvent.Unknown) { tenantId =>
                SyncEvent.ResourcesUpdated(
                  tenantId = TenantId(tenantId),
                  id = ResourceId(payload.id),
                  op = SyncEvent.Op.valueOf(payload.op),
                )
              }
            }
          case "preset_change" =>
            parsePayload(notification.getParameter).fold[SyncEvent](SyncEvent.Unknown) { payload =>
              SyncEvent.PresetsUpdated(
                tenantId = TenantId(payload.tenantId.getOrElse("")),
                id = PresetId(payload.id),
                op = SyncEvent.Op.valueOf(payload.op),
              )
            }
          case "form_change" =>
            parsePayload(notification.getParameter).fold[SyncEvent](SyncEvent.Unknown) { payload =>
              SyncEvent.FormsUpdated(
                id = FormId(payload.id),
                version = payload.version.getOrElse(0),
                op = SyncEvent.Op.valueOf(payload.op),
              )
            }
          case "otp_template_change" =>
            parsePayload(notification.getParameter).fold[SyncEvent](SyncEvent.Unknown) { payload =>
              payload.tenantId.fold[SyncEvent](SyncEvent.Unknown) { tenantId =>
                SyncEvent.OtpTemplatesUpdated(
                  tenantId = TenantId(tenantId),
                  id = payload.id,
                  op = SyncEvent.Op.valueOf(payload.op),
                )
              }
            }
          case "challenge_settings_change" =>
            parsePayload(notification.getParameter).fold[SyncEvent](SyncEvent.Unknown) { payload =>
              payload.tenantId.fold[SyncEvent](SyncEvent.Unknown) { tenantId =>
                SyncEvent.ChallengeSettingsUpdated(
                  tenantId = TenantId(tenantId),
                  op = SyncEvent.Op.valueOf(payload.op),
                )
              }
            }
          case "theme_change" =>
            SyncEvent.ThemesUpdated
          case "system_settings_change" =>
            SyncEvent.SystemSettingsUpdated
          case _ =>
            SyncEvent.Unknown
      }

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
      yield PostgresCacheSyncRepository(conn)
