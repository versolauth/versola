package versola.central.antifraud

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

/** The rule names a rule can be fired under; auth's catalogue. */
case class AntifraudRulesResponse(rules: List[String]) derives JsonCodec, Schema

case class AntifraudDecisionStatus(enabled: Boolean) derives JsonCodec, Schema

/** `snapshotAgeSeconds` is the age of the snapshot being enforced (absent before the first load). */
case class AntifraudListsStatus(
    enabled: Boolean,
    snapshotAgeSeconds: Option[Long],
    size: Int,
    skipped: Int,
    lastRefreshError: Option[String],
) derives JsonCodec, Schema

/** `version` is the version of the settings this instance holds, the `ETag` of `GET /antifraud/settings`; `ageSeconds` is the
  * time since it last checked it against the database successfully. */
case class AntifraudTuningStatus(
    available: Boolean,
    version: Option[Long],
    ageSeconds: Option[Long],
    lastRefreshError: Option[String],
) derives JsonCodec, Schema

/** What one auth instance says of its antifraud (the one that answers: another may be behind, the ages say by how much).
  *
  * @param cellShare the share of a limit by address (`ip`, `asn`, `prefix`) this cell applies, so that the limit holds
  *   for the deployment and not once per cell (versolauth/versola#1052): its weight among the cells that take users.
  *   A limit of 100 at a share of 0.34 fires above 34 in this cell. `1` where there is one auth; absent from an auth
  *   that does not report it.
  */
case class AntifraudInstanceStatus(
    decision: AntifraudDecisionStatus,
    lists: AntifraudListsStatus,
    tuning: AntifraudTuningStatus,
    cellShare: Option[Double],
) derives JsonCodec, Schema

/** What an instance of one cell says, or why the cell did not answer (`error`, and then no `status`). `tuning.version` is the
  * version of central's settings the cell has copied: the same number in every cell once a change has reached them all. */
case class AntifraudCellStatus(cell: String, status: Option[AntifraudInstanceStatus], error: Option[String]) derives JsonCodec, Schema

/** The status of the `auth` central is configured with, and of an instance of every registered cell (none where there is
  * no cell): the policy is one, but each cell copies and enforces it on its own. */
case class AntifraudStatusResponse(
    decision: AntifraudDecisionStatus,
    lists: AntifraudListsStatus,
    tuning: AntifraudTuningStatus,
    cells: List[AntifraudCellStatus],
) derives JsonCodec, Schema
