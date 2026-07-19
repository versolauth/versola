package versola.configuration.sync

import versola.central.configuration.clients.{ClientId, PresetId}
import versola.central.configuration.forms.FormId
import versola.central.configuration.permissions.Permission
import versola.central.configuration.resources.ResourceId
import versola.central.configuration.roles.RoleId
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.TenantId
import zio.test.*

/** Pure parsing tests for [[PostgresCacheSyncRepository.parseNotification]] (no database required).
  *
  * Covers the totality guarantees from issue #63: malformed JSON, an unrecognized `op`, and a
  * missing/empty `tenantId` (for channels that require one) must all fall back to
  * `SyncEvent.Unknown` instead of throwing or producing an empty-tenant event.
  */
object PostgresCacheSyncRepositorySpec extends ZIOSpecDefault:

  import PostgresCacheSyncRepository.parseNotification

  def spec = suite("PostgresCacheSyncRepository.parseNotification")(
    suite("payload-less channels")(
      test("tenant_change ignores the payload") {
        assertTrue(parseNotification("tenant_change", "") == SyncEvent.TenantsUpdated)
      },
      test("edge_change ignores the payload") {
        assertTrue(parseNotification("edge_change", "") == SyncEvent.EdgesUpdated)
      },
      test("jwks_change ignores the payload") {
        assertTrue(parseNotification("jwks_change", "") == SyncEvent.JwksUpdated)
      },
      test("theme_change ignores the payload") {
        assertTrue(parseNotification("theme_change", "") == SyncEvent.ThemesUpdated)
      },
      test("system_settings_change ignores the payload") {
        assertTrue(parseNotification("system_settings_change", "") == SyncEvent.SystemSettingsUpdated)
      },
      test("unrecognized channel falls back to Unknown") {
        assertTrue(parseNotification("some_future_channel", "") == SyncEvent.Unknown)
      },
    ),
    suite("channels with no tenantId in the payload")(
      test("client_change parses a well-formed payload") {
        val payload = """{"id":"c1","op":"UPDATE"}"""
        assertTrue(
          parseNotification("client_change", payload) ==
            SyncEvent.ClientsUpdated(id = ClientId("c1"), op = SyncEvent.Op.UPDATE),
        )
      },
      test("client_change falls back to Unknown on malformed JSON") {
        assertTrue(parseNotification("client_change", "{not json") == SyncEvent.Unknown)
      },
      test("client_change falls back to Unknown on an unrecognized op") {
        val payload = """{"id":"c1","op":"TRUNCATE"}"""
        assertTrue(parseNotification("client_change", payload) == SyncEvent.Unknown)
      },
      test("preset_change parses a well-formed payload without tenantId") {
        // notify_preset_change() never sends tenantId (authorization_presets has no tenant_id
        // column); PresetsUpdated.matches/sort don't use it either, so it must not be required.
        val payload = """{"id":"preset1","op":"DELETE"}"""
        assertTrue(
          parseNotification("preset_change", payload) ==
            SyncEvent.PresetsUpdated(tenantId = TenantId(""), id = PresetId("preset1"), op = SyncEvent.Op.DELETE),
        )
      },
      test("preset_change falls back to Unknown on an unrecognized op") {
        val payload = """{"id":"preset1","op":"TRUNCATE"}"""
        assertTrue(parseNotification("preset_change", payload) == SyncEvent.Unknown)
      },
    ),
    suite("tenant-scoped channels")(
      test("role_change parses a well-formed payload") {
        val payload = """{"tenantId":"t1","id":"r1","op":"INSERT"}"""
        assertTrue(
          parseNotification("role_change", payload) ==
            SyncEvent.RolesUpdated(tenantId = TenantId("t1"), id = RoleId("r1"), op = SyncEvent.Op.INSERT),
        )
      },
      test("role_change falls back to Unknown when tenantId is missing") {
        val payload = """{"id":"r1","op":"INSERT"}"""
        assertTrue(parseNotification("role_change", payload) == SyncEvent.Unknown)
      },
      test("role_change falls back to Unknown when tenantId is empty") {
        val payload = """{"tenantId":"","id":"r1","op":"INSERT"}"""
        assertTrue(parseNotification("role_change", payload) == SyncEvent.Unknown)
      },
      test("scope_change parses a well-formed payload") {
        val payload = """{"tenantId":"t1","id":"read","op":"DELETE"}"""
        assertTrue(
          parseNotification("scope_change", payload) ==
            SyncEvent.ScopesUpdated(tenantId = TenantId("t1"), id = ScopeToken("read"), op = SyncEvent.Op.DELETE),
        )
      },
      test("scope_change falls back to Unknown when tenantId is missing") {
        val payload = """{"id":"read","op":"DELETE"}"""
        assertTrue(parseNotification("scope_change", payload) == SyncEvent.Unknown)
      },
      test("scope_change falls back to Unknown when tenantId is empty") {
        val payload = """{"tenantId":"","id":"read","op":"DELETE"}"""
        assertTrue(parseNotification("scope_change", payload) == SyncEvent.Unknown)
      },
      test("permission_change parses a well-formed payload") {
        val payload = """{"tenantId":"t1","id":"p1","op":"UPDATE"}"""
        assertTrue(
          parseNotification("permission_change", payload) ==
            SyncEvent.PermissionsUpdated(tenantId = TenantId("t1"), id = Permission("p1"), op = SyncEvent.Op.UPDATE),
        )
      },
      test("permission_change falls back to Unknown when tenantId is missing") {
        val payload = """{"id":"p1","op":"UPDATE"}"""
        assertTrue(parseNotification("permission_change", payload) == SyncEvent.Unknown)
      },
      test("permission_change falls back to Unknown when tenantId is empty") {
        val payload = """{"tenantId":"","id":"p1","op":"UPDATE"}"""
        assertTrue(parseNotification("permission_change", payload) == SyncEvent.Unknown)
      },
      test("resource_change parses a well-formed payload") {
        val payload = """{"tenantId":"t1","id":"res1","op":"INSERT"}"""
        assertTrue(
          parseNotification("resource_change", payload) ==
            SyncEvent.ResourcesUpdated(tenantId = TenantId("t1"), id = ResourceId("res1"), op = SyncEvent.Op.INSERT),
        )
      },
      test("resource_change falls back to Unknown when tenantId is missing") {
        val payload = """{"id":"res1","op":"INSERT"}"""
        assertTrue(parseNotification("resource_change", payload) == SyncEvent.Unknown)
      },
      test("resource_change falls back to Unknown when tenantId is empty") {
        val payload = """{"tenantId":"","id":"res1","op":"INSERT"}"""
        assertTrue(parseNotification("resource_change", payload) == SyncEvent.Unknown)
      },
      test("otp_template_change parses a well-formed payload") {
        val payload = """{"tenantId":"t1","id":"otp1","op":"UPDATE"}"""
        assertTrue(
          parseNotification("otp_template_change", payload) ==
            SyncEvent.OtpTemplatesUpdated(tenantId = TenantId("t1"), id = "otp1", op = SyncEvent.Op.UPDATE),
        )
      },
      test("otp_template_change falls back to Unknown when tenantId is missing") {
        val payload = """{"id":"otp1","op":"UPDATE"}"""
        assertTrue(parseNotification("otp_template_change", payload) == SyncEvent.Unknown)
      },
      test("otp_template_change falls back to Unknown when tenantId is empty") {
        val payload = """{"tenantId":"","id":"otp1","op":"UPDATE"}"""
        assertTrue(parseNotification("otp_template_change", payload) == SyncEvent.Unknown)
      },
      test("challenge_settings_change parses a well-formed payload") {
        val payload = """{"tenantId":"t1","id":"ignored","op":"UPDATE"}"""
        assertTrue(
          parseNotification("challenge_settings_change", payload) ==
            SyncEvent.ChallengeSettingsUpdated(tenantId = TenantId("t1"), op = SyncEvent.Op.UPDATE),
        )
      },
      test("challenge_settings_change falls back to Unknown when tenantId is missing") {
        val payload = """{"id":"ignored","op":"UPDATE"}"""
        assertTrue(parseNotification("challenge_settings_change", payload) == SyncEvent.Unknown)
      },
      test("challenge_settings_change falls back to Unknown when tenantId is empty") {
        val payload = """{"tenantId":"","id":"ignored","op":"UPDATE"}"""
        assertTrue(parseNotification("challenge_settings_change", payload) == SyncEvent.Unknown)
      },
    ),
    suite("form_change (versioned, no tenant)")(
      test("parses a well-formed payload with version") {
        val payload = """{"id":"f1","version":3,"op":"UPDATE"}"""
        assertTrue(
          parseNotification("form_change", payload) ==
            SyncEvent.FormsUpdated(id = FormId("f1"), version = 3, op = SyncEvent.Op.UPDATE),
        )
      },
      test("defaults version to 0 when absent") {
        val payload = """{"id":"f1","op":"INSERT"}"""
        assertTrue(
          parseNotification("form_change", payload) ==
            SyncEvent.FormsUpdated(id = FormId("f1"), version = 0, op = SyncEvent.Op.INSERT),
        )
      },
    ),
  )
