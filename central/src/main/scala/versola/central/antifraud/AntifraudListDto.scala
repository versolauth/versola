package versola.central.antifraud

import versola.central.configuration.tenants.TenantId
import versola.central.users.UserId
import versola.util.{JsonStringSchema, PageLimit, PageOffset}
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

import java.time.Instant
import java.util.UUID

/** What an entry makes the rules do; see auth's `AntifraudLists`. */
enum AntifraudListType derives JsonCodec:
  case deny, allow, watch, fake

object AntifraudListType:
  given Schema[AntifraudListType] = JsonStringSchema.of

/** What the `value` of an entry is compared with. */
enum AntifraudValueType derives JsonCodec:
  case phone_hash, prefix, ip, cidr, asn, device_id, ua_hash, ja4_hash, email_domain

object AntifraudValueType:
  given Schema[AntifraudValueType] = JsonStringSchema.of

enum AntifraudListAction derives JsonCodec:
  case create, delete, expire

/** A request to add or preview an entry. Give `value`, or for a `phone_hash` entry `phone` instead (auth hashes it).
  * `userId` is the admin, which edge puts in from the verified token (see [[AntifraudListController]]); it is what auth
  * records as who added the entry. The same body goes to auth. */
case class NewListEntryRequest(
    listType: AntifraudListType,
    valueType: AntifraudValueType,
    value: Option[String],
    phone: Option[String],
    tenantId: Option[TenantId],
    expiresAt: Option[Instant],
    reason: Option[String],
    source: Option[String],
    shadow: Option[Boolean],
    userId: UserId,
) derives JsonCodec, Schema

case class DeleteListEntryRequest(id: UUID, userId: UserId) derives JsonCodec, Schema

/** What a listing is narrowed to; `global` is the global entries only. */
case class ListEntriesQuery(
    listType: Option[AntifraudListType],
    valueType: Option[AntifraudValueType],
    tenantId: Option[TenantId],
    global: Option[Boolean],
    limit: Option[PageLimit],
    offset: Option[PageOffset],
)

case class ListEntryResponse(
    id: UUID,
    listType: AntifraudListType,
    valueType: AntifraudValueType,
    value: String,
    tenantId: Option[TenantId],
    expiresAt: Option[Instant],
    reason: Option[String],
    source: String,
    shadow: Boolean,
    createdBy: String,
    createdAt: Instant,
    hitCount: Long,
    lastHitAt: Option[Instant],
) derives JsonCodec, Schema

case class ListEntriesResponse(entries: List[ListEntryResponse]) derives JsonCodec, Schema

/** `matched` is `None` for a kind of value the event log has no column for. */
case class PreviewResponse(matched: Option[Long], total: Long) derives JsonCodec, Schema

case class AuditRecordResponse(
    id: UUID,
    at: Instant,
    action: AntifraudListAction,
    actor: String,
    entryId: UUID,
    listType: AntifraudListType,
    valueType: AntifraudValueType,
    value: String,
    tenantId: Option[TenantId],
    expiresAt: Option[Instant],
    reason: Option[String],
) derives JsonCodec, Schema

case class AuditResponse(records: List[AuditRecordResponse]) derives JsonCodec, Schema
