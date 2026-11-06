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
  case phone_hash, prefix, ip, cidr, asn, device_id, ua_hash, ja4_hash, email_domain, country

object AntifraudValueType:
  given Schema[AntifraudValueType] = JsonStringSchema.of

enum AntifraudListAction derives JsonCodec:
  case create, update, delete, expire

/** A request to add or preview an entry. Give `value`, or for a `phone_hash` entry `phone` instead (auth hashes it). There is no
  * `source`: auth says `manual` for every request of this API, so a client cannot name its own.
  * `userId` is the admin, which edge puts in from the verified token (see [[AntifraudListController]]); it is what auth
  * records as who added the entry; `callerTenantId` is the tenant of that token, which a tenant route needs and an operator
  * route ignores. Auth is sent the entry with the caller's scope, which central sets from the route, not from this body. */
case class NewListEntryRequest(
    listType: AntifraudListType,
    valueType: AntifraudValueType,
    value: Option[String],
    phone: Option[String],
    tenantId: Option[TenantId],
    expiresAt: Option[Instant],
    reason: Option[String],
    shadow: Option[Boolean],
    userId: UserId,
    callerTenantId: Option[TenantId],
) derives JsonCodec, Schema

/** `callerTenantId` is, like `userId`, what edge adds from the verified token; a tenant route reads it (see
  * [[AntifraudCaller]]), an operator route does not. */
case class DeleteListEntryRequest(id: UUID, userId: UserId, callerTenantId: Option[TenantId]) derives JsonCodec, Schema

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

/** What an entry had before an update changed it. */
case class PreviousListValuesResponse(
    expiresAt: Option[Instant],
    reason: Option[String],
    source: String,
    shadow: Boolean,
) derives JsonCodec, Schema

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
    previous: Option[PreviousListValuesResponse],
) derives JsonCodec, Schema

case class AuditResponse(records: List[AuditRecordResponse]) derives JsonCodec, Schema
