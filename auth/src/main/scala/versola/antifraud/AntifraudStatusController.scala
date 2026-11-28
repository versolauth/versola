package versola.antifraud

import versola.user.authorizeInternal
import versola.util.CoreConfig
import versola.util.http.Controller
import zio.*
import zio.http.{Method, Request, Response, Routes, handler}
import zio.json.{EncoderOps, JsonCodec}

import java.time.{Duration as JDuration, Instant}

/** What an operator asks of a running instance: which rule names exist, and whether the antifraud on this instance is on and
  * has what it needs. Both are read from memory; internal API, called by central as the other antifraud endpoints are.
  *
  * The status is of the instance that answers: another one may be behind (the age says by how much).
  */
object AntifraudStatusController extends Controller:
  type Env = Tracing & CoreConfig & AntifraudLists & AntifraudTuningStore & AntifraudEvents.Settings & CellShare

  def routes: Routes[Env, Throwable] = Routes(rulesEndpoint, statusEndpoint)

  /** The names a rule is fired under, which the settings may name (see [[Antifraud.ruleNames]]), in order. */
  val rulesEndpoint =
    Method.GET / "antifraud" / "rules" -> handler { (request: Request) =>
      authorizeInternal(request).as(Response.json(RulesResponse(Antifraud.ruleNames.toList.sorted).toJson))
    }

  val statusEndpoint =
    Method.GET / "antifraud" / "status" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        settings <- ZIO.service[AntifraudEvents.Settings]
        lists <- ZIO.serviceWithZIO[AntifraudLists](_.status)
        tuning <- ZIO.serviceWithZIO[AntifraudTuningStore](_.status)
        share <- ZIO.serviceWithZIO[CellShare](_.current)
        now <- Clock.instant
      yield Response.json(
        StatusResponse(
          decision = DecisionStatus(settings.decision.enabled),
          lists = ListsStatusResponse(
            enabled = lists.enabled,
            snapshotAgeSeconds = age(lists.loadedAt, now),
            size = lists.size,
            skipped = lists.skipped,
            lastRefreshError = lists.lastError,
          ),
          tuning = TuningStatusResponse(
            available = tuning.available,
            version = tuning.version,
            ageSeconds = age(tuning.checkedAt, now),
            lastRefreshError = tuning.lastError,
          ),
          cellShare = share.value,
        ).toJson,
      )
    }

  private def age(since: Option[Instant], now: Instant): Option[Long] =
    since.map(at => math.max(0L, JDuration.between(at, now).getSeconds))

  case class RulesResponse(rules: List[String]) derives JsonCodec

  case class DecisionStatus(enabled: Boolean) derives JsonCodec

  /** `snapshotAgeSeconds` is the age of the snapshot being enforced (absent before the first load). */
  case class ListsStatusResponse(
      enabled: Boolean,
      snapshotAgeSeconds: Option[Long],
      size: Int,
      skipped: Int,
      lastRefreshError: Option[String],
  ) derives JsonCodec

  /** `version` is the version of the settings row this instance holds, the `ETag` of `GET /antifraud/settings`;
    * `ageSeconds` is the time since it last checked it against the database successfully. */
  case class TuningStatusResponse(
      available: Boolean,
      version: Option[Long],
      ageSeconds: Option[Long],
      lastRefreshError: Option[String],
  ) derives JsonCodec

  /** `cellShare` is the part of a limit by address (`ip`, `asn`, `prefix`) this cell applies, from 0 (exclusive) to 1
    * (versolauth/versola#1052): a limit of 100 is applied as 34 where the share is 0.34 (`ceil(100 * 0.34)`; a count above it fires the rule). It is 1 where this
    * auth is no cell or no cell is registered. */
  case class StatusResponse(
      decision: DecisionStatus,
      lists: ListsStatusResponse,
      tuning: TuningStatusResponse,
      cellShare: Double,
  ) derives JsonCodec
