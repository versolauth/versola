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

  /** An autonomous system number (`64500` or `AS64500`), against [[AntifraudContext.asn]]: the AS the ASN database gives the
    * request's address. */
  case asn
  case device_id

  /** Lower-case hex SHA-256 of the user agent. */
  case ua_hash

  /** A JA4 fingerprint, as it is in [[AntifraudContext.ja4]]. */
  case ja4_hash

  /** The domain of an address, from the context signal [[AntifraudLists.EmailDomainSignal]]. */
  case email_domain

  /** An ISO 3166-1 alpha-2 country code (`KZ`), against the context signal [[GeoRules.CountrySignal]]: the country the
    * address of the request is in. */
  case country

object AntifraudValueType:
  given Schema[AntifraudValueType] = JsonStringSchema.of

/** An entry of central's lists as a cell copies it into `af_list_entry`. The kinds are text: one this build does not know
  * is stored as it is and skipped when the entries are read ([[ListedValue]]), so that central may be a version ahead.
  *
  * @param tenantId   `None` applies to every tenant
  * @param expiresAt  `None` never expires
  * @param source     who or what put it on the list (`manual`, a detection rule)
  * @param shadow     matched and counted, never enforced: the rule it fires is `shadow_list:<value type>` with no weight
  */
case class PolicyListEntry(
    id: UUID,
    listType: String,
    valueType: String,
    value: String,
    tenantId: Option[String],
    expiresAt: Option[Instant],
    reason: Option[String],
    source: String,
    shadow: Boolean,
    createdBy: String,
    createdAt: Instant,
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

/** Hits of one entry: since the last flush where they are counted, all of this cell's where they are read. */
case class ListHit(id: UUID, count: Long, lastAt: Instant) derives JsonCodec

/** An entry central asks about before it stores it. `source` is never the client's: central says `manual` for a request of
  * its API and `detector:<kind>` for an approved proposal. */
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

/** Who put an entry on the list; set by the server, never read from a request. */
object ListEntrySource:
  val Manual = "manual"

/** How many events of a window an entry would have matched; `matched` is `None` for a value the event log has no
  * column for (an address, a device, a fingerprint, a mail domain). */
case class ListPreview(matched: Option[Long], total: Long) derives JsonCodec
