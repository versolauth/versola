package versola.central.antifraud

import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec

import java.time.Instant
import java.util.UUID

/** An entry of the lists as central stores it (a row of `antifraud_list_entries`) and as a cell copies it. `value` is what
  * auth made of the request: normalised, and for a `phone_hash` the keyed hash. How often it matched is not here: the cells
  * count that, each for itself (see [[ListHits]]).
  *
  * @param tenantId `None` applies to every tenant
  * @param source   who or what put it on the list; the server's, never read from a request
  * @param shadow   matched and counted, never enforced
  */
case class ListEntryRecord(
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
) derives JsonCodec

/** @param tenantId  `None` does not filter; `Some(None)` is the global entries only
  * @param visibleTo `Some(t)` leaves the global entries and tenant `t`'s (what a tenant admin sees)
  */
case class ListEntryFilter(
    listType: Option[AntifraudListType],
    valueType: Option[AntifraudValueType],
    tenantId: Option[Option[TenantId]],
    visibleTo: Option[TenantId],
)

/** How often an entry matched in one cell, as that cell counts it. */
case class ListHits(id: UUID, count: Long, lastAt: Instant) derives JsonCodec

/** What auth is asked of an entry before it is stored: whether it is valid, and what its value is. `phone` replaces `value`
  * for a `phone_hash` entry (auth has the key of the hash). `tenantId` and `source` are central's, from the caller's scope;
  * `preview` also asks how many events of the last 24 hours the entry would have matched in that cell. */
case class ListEntryCheck(
    listType: AntifraudListType,
    valueType: AntifraudValueType,
    value: Option[String],
    phone: Option[String],
    tenantId: Option[TenantId],
    expiresAt: Option[Instant],
    reason: Option[String],
    source: String,
    shadow: Boolean,
    preview: Boolean,
) derives JsonCodec

/** Auth's answer to a [[ListEntryCheck]] it found valid: the value to store, and the preview where one was asked. */
case class CheckedListEntry(value: String, preview: Option[PreviewResponse]) derives JsonCodec

/** What auth is asked of the settings before they are stored: `patch` applied to `settings` (everything off where there are
  * none), validated as a whole. A replacement names the settings and no patch; neither is "everything off", which only
  * auth can say (the defaults of a setting are its own). */
case class SettingsCheck(settings: Option[AntifraudSettings], patch: Option[AntifraudSettingsPatch]) derives JsonCodec

/** What a cell reads to know whether its copy is current: the settings with their version (none at version 0) and the
  * version of the lists. The settings travel with it because they are one small row. */
case class PolicyState(settingsVersion: Long, settings: Option[AntifraudSettings], listsVersion: Long) derives JsonCodec

/** The lists as a cell copies them, and the version they were read at (versolauth/versola#1052).
  *
  * `full`: `entries` is every entry that has not expired and `removed` is empty; the cell replaces its copy. Not `full`:
  * the cell was at the version it asked `since`, and `entries` are the entries changed after it that are still stored and
  * not expired, as they are now, and `removed` the ids changed after it that are not (deleted, or expired since): the cell
  * drops `removed`, stores `entries`, and is then at `version`. */
case class PolicyLists(version: Long, full: Boolean, entries: List[ListEntryRecord], removed: List[UUID]) derives JsonCodec

/** Who put an entry on the list. */
object ListEntrySource:
  /** A request of the admin API, whatever its body says. */
  val Manual = "manual"
