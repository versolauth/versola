package versola.antifraud

import versola.oauth.client.model.TenantId
import versola.util.JsonStringSchema
import zio.json.JsonCodec
import zio.schema.Schema

import java.time.Instant
import java.util.UUID

/** What a list entry makes the rules do. See [[AntifraudLists]] for how they combine. */
enum AntifraudListType derives JsonCodec:
  /** Block. */
  case deny

  /** Outweighs the score of the other rules; never a deny of the same specificity. */
  case allow

  /** Adds its weight to the score and nothing else. */
  case watch

  /** Answer as if the step succeeded and do nothing. */
  case fake

object AntifraudListType:
  given Schema[AntifraudListType] = JsonStringSchema.of

/** What the `value` of an entry is compared with. */
enum AntifraudValueType derives JsonCodec:
  /** The keyed hash of a destination, as the event log stores it ([[AntifraudContext.subjectHash]]). */
  case phone_hash

  /** Leading digits of a phone number (at most [[AntifraudEvents.destPrefixLength]]), against [[AntifraudContext.destPrefix]]. */
  case prefix

  /** One IPv4 or IPv6 address. */
  case ip

  /** A network, `address/length`. */
  case cidr

  /** An autonomous system number. */
  case asn
  case device_id

  /** Lower-case hex SHA-256 of the user agent. */
  case ua_hash

  /** A JA4 fingerprint, as it is in [[AntifraudContext.ja4]]. */
  case ja4_hash

  /** The domain of an address, from the context signal [[AntifraudLists.EmailDomainSignal]]. */
  case email_domain

object AntifraudValueType:
  given Schema[AntifraudValueType] = JsonStringSchema.of

/** A row of `af_list_entry`.
  *
  * @param tenantId   `None` applies to every tenant
  * @param expiresAt  `None` never expires
  * @param source     who or what put it on the list (`manual`, a detection rule, an import)
  * @param shadow     matched and counted, never enforced: the rule it fires is `shadow_list:<value type>` with no weight
  * @param hitCount   how many times it decided or scored a step; kept in memory by the instances and written in batches, so it lags
  */
case class AntifraudListEntry(
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
) derives JsonCodec

/** What the cache needs of an entry: the key and how it applies, not the bookkeeping. */
case class ListedValue(
    id: UUID,
    listType: AntifraudListType,
    valueType: AntifraudValueType,
    value: String,
    tenantId: Option[TenantId],
    expiresAt: Option[Instant],
    shadow: Boolean,
)

/** Hits of one entry since the last flush. */
case class ListHit(id: UUID, count: Long, lastAt: Instant)

/** What a request to add an entry says; the service fills in the id and the time. */
case class NewListEntry(
    listType: AntifraudListType,
    valueType: AntifraudValueType,
    value: String,
    tenantId: Option[TenantId],
    expiresAt: Option[Instant],
    reason: Option[String],
    source: String,
    shadow: Boolean,
)

/** Who changes the lists. An operator (no tenant) may change any entry, including global ones, which apply to every
  * tenant; a tenant's admin only the entries of their own tenant. The HTTP endpoint maps the caller to this. */
case class ListActor(name: String, tenantId: Option[TenantId]):
  def isOperator: Boolean = tenantId.isEmpty
  def mayChange(entryTenant: Option[TenantId]): Boolean = isOperator || (entryTenant.isDefined && entryTenant == tenantId)

/** How many events of a window an entry would have matched; `matched` is `None` for a value the event log has no
  * column for (an address, a device, a fingerprint, an ASN, a mail domain). */
case class ListPreview(matched: Option[Long], total: Long) derives JsonCodec

enum AntifraudListAction derives JsonCodec:
  case create, delete, expire

/** One change of the lists. The entry is copied in because the row it describes may be gone. */
case class AntifraudListAudit(
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
) derives JsonCodec

/** @param tenantId `None` does not filter; `Some(None)` is the global entries only */
case class AntifraudListFilter(
    listType: Option[AntifraudListType],
    valueType: Option[AntifraudValueType],
    tenantId: Option[Option[TenantId]],
)

object AntifraudListFilter:
  val all: AntifraudListFilter = AntifraudListFilter(listType = None, valueType = None, tenantId = None)
