package versola.central.configuration.sync

import versola.central.configuration.challenges.{ChallengeSettingsRecord, OtpTemplateRecord}
import versola.central.configuration.clients.{AuthorizationPreset, ClientId, OAuthClientRecord, PresetId}
import versola.central.configuration.forms.{FormId, FormRecord}
import versola.central.configuration.permissions.{Permission, PermissionRecord}
import versola.central.configuration.resources.{ResourceId, ResourceRecord}
import versola.central.configuration.roles.{RoleId, RoleRecord}
import versola.central.configuration.scopes.{ScopeRecord, ScopeToken}
import versola.central.configuration.tenants.TenantId

sealed trait SyncEvent

object SyncEvent:
  sealed trait ModifyCache extends SyncEvent:
    type ID
    type Record

    def op: Op

    def matches(record: Record): Boolean

    def sort(records: Vector[Record]): Vector[Record]


  case object Unknown extends SyncEvent
  case object TenantsUpdated extends SyncEvent
  case object EdgesUpdated extends SyncEvent
  case class ClientsUpdated(
      id: ClientId,
      op: Op,
  ) extends ModifyCache:
    type ID = ClientId
    type Record = OAuthClientRecord

    def matches(record: OAuthClientRecord): Boolean =
      id == record.id

    def sort(records: Vector[OAuthClientRecord]): Vector[OAuthClientRecord] =
      records.sortBy(_.id)


  case class ScopesUpdated(
      tenantId: TenantId,
      id: ScopeToken,
      op: Op,
  ) extends ModifyCache:
    type ID = ScopeToken
    type Record = ScopeRecord

    def matches(record: ScopeRecord): Boolean =
      tenantId == record.tenantId && id == record.id

    def sort(records: Vector[ScopeRecord]): Vector[ScopeRecord] =
      records.sortBy(x => (x.tenantId, x.id))

  case class RolesUpdated(
      tenantId: TenantId,
      id: RoleId,
      op: Op,
  ) extends ModifyCache:
    type ID = RoleId
    type Record = RoleRecord

    def matches(record: RoleRecord): Boolean =
      tenantId == record.tenantId && id == record.id

    def sort(records: Vector[RoleRecord]): Vector[RoleRecord] =
      records.sortBy(x => (x.tenantId, x.id))


  case class PermissionsUpdated(
      tenantId: Option[TenantId],
      id: Permission,
      op: Op,
  ) extends ModifyCache:
    type ID = Permission
    type Record = PermissionRecord

    def matches(record: PermissionRecord): Boolean =
      tenantId == record.tenantId && id == record.id

    def sort(records: Vector[PermissionRecord]): Vector[PermissionRecord] =
      records.sortBy(x => (x.tenantId, x.id))

  case class ResourcesUpdated(
      tenantId: TenantId,
      id: ResourceId,
      op: Op,
  ) extends ModifyCache:
    type ID = ResourceId
    type Record = ResourceRecord

    def matches(record: ResourceRecord): Boolean =
      tenantId == record.tenantId && id == record.id

    def sort(records: Vector[ResourceRecord]): Vector[ResourceRecord] =
      records.sortBy(x => (x.tenantId, x.id))

  case class PresetsUpdated(
      tenantId: TenantId,
      id: PresetId,
      op: Op,
  ) extends ModifyCache:
    type ID = PresetId
    type Record = AuthorizationPreset

    def matches(record: AuthorizationPreset): Boolean =
      id == record.id

    def sort(records: Vector[AuthorizationPreset]): Vector[AuthorizationPreset] =
      records.sortBy(x => (x.clientId, x.id))

  case class FormsUpdated(
      id: FormId,
      version: Int,
      op: Op,
  ) extends ModifyCache:
    type Record = FormRecord

    def matches(record: FormRecord): Boolean =
      id == record.id && version == record.version

    def sort(records: Vector[FormRecord]): Vector[FormRecord] =
      records.sortBy(r => (r.id, -r.version))


  case class OtpTemplatesUpdated(
      tenantId: TenantId,
      id: String,
      op: Op,
  ) extends ModifyCache:
    type Record = OtpTemplateRecord

    def matches(record: OtpTemplateRecord): Boolean =
      tenantId == record.tenantId && id == record.id

    def sort(records: Vector[OtpTemplateRecord]): Vector[OtpTemplateRecord] =
      records.sortBy(r => (r.tenantId, r.id))

  case class ChallengeSettingsUpdated(
      tenantId: TenantId,
      op: Op,
  ) extends ModifyCache:
    type Record = ChallengeSettingsRecord

    def matches(record: ChallengeSettingsRecord): Boolean =
      tenantId == record.tenantId

    def sort(records: Vector[ChallengeSettingsRecord]): Vector[ChallengeSettingsRecord] =
      records.sortBy(_.tenantId)

  enum Op:
    case INSERT, UPDATE, DELETE
