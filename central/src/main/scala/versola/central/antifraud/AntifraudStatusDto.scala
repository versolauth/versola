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

/** What one auth instance says of its antifraud (the one that answers: another may be behind, the ages say by how much). */
case class AntifraudStatusResponse(
    decision: AntifraudDecisionStatus,
    lists: AntifraudListsStatus,
    tuning: AntifraudTuningStatus,
) derives JsonCodec, Schema
