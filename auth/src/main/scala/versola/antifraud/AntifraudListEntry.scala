package versola.antifraud

import versola.oauth.client.model.TenantId

import java.time.Instant
import java.util.UUID

/** What a list entry makes the rules do. See [[AntifraudLists]] for how they combine. */
enum AntifraudListType:
  /** Block. */
  case deny
  /** Outweighs the score of the other rules; never a deny of the same specificity. */
  case allow
  /** Adds its weight to the score and nothing else. */
  case watch
  /** Answer as if the step succeeded and do nothing. */
  case fake

/** What the `value` of an entry is compared with. */
enum AntifraudValueType:
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

/** A row of `af_list_entry`.
  *
  * @param tenantId   `None` applies to every tenant
  * @param expiresAt  `None` never expires
  * @param source     who or what put it on the list (`manual`, a detection rule, an import)
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
    createdBy: String,
    createdAt: Instant,
    hitCount: Long = 0,
    lastHitAt: Option[Instant] = None,
)

/** What the cache needs of an entry: the key and how it applies, not the bookkeeping. */
case class ListedValue(
    id: UUID,
    listType: AntifraudListType,
    valueType: AntifraudValueType,
    value: String,
    tenantId: Option[TenantId],
    expiresAt: Option[Instant],
)

/** Hits of one entry since the last flush. */
case class ListHit(id: UUID, count: Long, lastAt: Instant)

/** What a request to add an entry says; the service fills in the id and the time. */
case class NewListEntry(
    listType: AntifraudListType,
    valueType: AntifraudValueType,
    value: String,
    tenantId: Option[TenantId] = None,
    expiresAt: Option[Instant] = None,
    reason: Option[String] = None,
    source: String = "manual",
)

enum AntifraudListAction:
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
)

/** @param tenantId `None` does not filter; `Some(None)` is the global entries only */
case class AntifraudListFilter(
    listType: Option[AntifraudListType] = None,
    valueType: Option[AntifraudValueType] = None,
    tenantId: Option[Option[TenantId]] = None,
)
