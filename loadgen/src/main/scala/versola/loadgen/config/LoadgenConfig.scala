package versola.loadgen.config

import zio.Config
import zio.Duration
import zio.config.magnolia.DeriveConfig

/** Root configuration tree for `loadgen`, decoded from HOCON via `VersolaApp.parseConfig`
  * (see versola-loadgen-dev-spec.md §5). One process, one file, one binary: `role` picks which
  * half runs; `shard` is present only when `role = driver`.
  *
  * Deviates from the dev spec in one respect: the spec's HOCON sketch uses `actions` for two
  * unrelated things (`session.actions` -- the NegBinomial action-count distribution -- and a
  * top-level `actions` list -- the ten business action definitions). Renamed the former to
  * `session.action-count` / [[ActionCountConfig]] here to remove the collision; the field
  * still means exactly what §5 describes.
  */
case class LoadgenConfig(
    role: LoadgenRole,
    shard: Option[ShardConfig],
    targets: TargetsConfig,
    coordinator: CoordinatorClientConfig,
    store: StoreConfig,
    population: PopulationConfig,
    session: SessionConfig,
    campaign: CampaignConfig,
    actions: List[BusinessActionConfig],
)

/** Which half of the `loadgen` binary this process runs. Same binary and image serve all four --
  * see versola-loadgen-dev-spec.md §12 (coordinator) and §7 (driver). `Seed`/`Provision` are the
  * one-shot `loadgen seed` / `loadgen provision` subcommands (§10, §4's `AdminClient`).
  */
enum LoadgenRole(val configValue: String):
  case Coordinator extends LoadgenRole("coordinator")
  case Driver extends LoadgenRole("driver")
  case Seed extends LoadgenRole("seed")
  case Provision extends LoadgenRole("provision")

object LoadgenRole:
  private val byValue: Map[String, LoadgenRole] = values.map(r => r.configValue -> r).toMap

  def fromConfigString(value: String): Either[String, LoadgenRole] =
    byValue.get(value)
      .toRight(s"Unknown loadgen role '$value' (expected one of: ${values.map(_.configValue).mkString(", ")})")

  // Same idiom as EnvName/Email/... in PostgresOAuthApp and EdgeConfigSpec: a plain string in
  // HOCON, validated and lifted into the enum at decode time rather than relying on
  // zio-config-magnolia's sealed-trait coproduct support (a discriminator-keyed shape that
  // doesn't match a plain `role = "driver"` string).
  given DeriveConfig[LoadgenRole] = DeriveConfig[String]
    .mapOrFail(str => fromConfigString(str).left.map(message => Config.Error.InvalidData(message = message)))

/** Which slice of the virtual-user population this driver owns: `shard = id % count`
  * (versola-loadgen-dev-spec.md §7.1). Optional because a coordinator/seed/provision process has
  * no shard to own -- their config files omit the block entirely, and requiring it here would
  * fail their decode before role dispatch ever runs. Role dispatch asserts it is present for
  * `driver`.
  */
case class ShardConfig(index: Int, count: Int)

/** The SUT and the mock backend this campaign is driven against. */
case class TargetsConfig(
    authUrl: String,
    edgeUrl: String,
    centralUrl: String,
    mockUrl: String,
    /** WebAuthn `rp.origin` the software authenticator signs against (§4, §8.3). */
    origin: String,
)

case class CoordinatorClientConfig(url: String, pollInterval: Duration)

case class WriteBehindConfig(flushInterval: Duration, batchSize: Int)

/** The emulator's own Postgres -- its bookkeeping, never the SUT's (§6). */
case class StoreConfig(
    url: String,
    maximumPoolSize: Int,
    writeBehind: WriteBehindConfig,
)

case class PopulationClassConfig(
    name: String,
    share: Double,
    activeProbability: Double,
    sessions: Int,
)

case class PlatformMixConfig(mobile: Double, web: Double)

case class CredentialMixConfig(otp: Double, otpPassword: Double, passkey: Double)

case class RoleMixConfig(retailUser: Double, retailBasic: Double)

case class PopulationConfig(
    target: Long,
    classes: List[PopulationClassConfig],
    platform: PlatformMixConfig,
    credentials: CredentialMixConfig,
    roles: RoleMixConfig,
)

case class FullLoginProbabilityConfig(mobile: Double, web: Double)

/** NegBinomial parameters for actions-per-session. Named `action-count` in HOCON, not
  * `actions`, to avoid colliding with the top-level business-action list -- see this file's
  * top comment.
  *
  * The means count the **user-driven** actions only. `GET /accounts` is issued by the app on
  * open, not chosen by the user, so `ActionCount` adds it outside the draw: a session averages
  * `1 + mean` actions, and design doc §2.3's 6.0 / 10.0 correspond to 5.0 / 9.0 here.
  *
  * `dispersion` is NB2 α, so `Var = mean × (1 + α × mean)`. Stated here and not only in the test
  * because one dispersion serving two different means only works if the parameter is
  * dimensionless; read as the size `r` instead, the same 0.6 would put the mode at 0, which is
  * the failure this parameterisation exists to avoid.
  */
case class ActionCountConfig(mobileMean: Double, webMean: Double, dispersion: Double)

case class ThinkTimeConfig(median: Duration, sigma: Double)

case class LogoutProbabilityConfig(mobile: Double, web: Double)

case class SessionConfig(
    fullLoginProbability: FullLoginProbabilityConfig,
    actionCount: ActionCountConfig,
    thinkTime: ThinkTimeConfig,
    paymentProbability: Double,
    extraRefreshProbability: Double,
    logoutProbability: LogoutProbabilityConfig,
    accessTokenTtl: Duration,
)

/** One phase of the campaign's arrival-rate envelope (§7.3). Which kind of phase it is, is
  * decided by **which fields are present**: `scale` alone is flat (`warmup`, `steady`, `spike`,
  * `recover`), `scaleFrom` + `scaleTo` is the linear `ramp`. Deliberately not decided by `name`,
  * which is a free-text metric label -- renaming `ramp` would otherwise silently change what the
  * phase does.
  *
  * Expressing that in the types (a sealed `Flat | Ramp`) is the better shape and is deferred: it
  * is a change to shared config surface with several tracks in flight. Until then the ambiguous
  * and incomplete combinations are rejected here rather than at first use, so a campaign that
  * cannot be represented fails the layer at boot instead of some minutes into a run.
  */
case class CampaignPhaseConfig(
    name: String,
    duration: Duration,
    scale: Option[Double],
    scaleFrom: Option[Double],
    scaleTo: Option[Double],
)

object CampaignPhaseConfig:
  /** Same idiom as [[LoadgenRole]]'s: validate during decode and fail the layer, rather than hand
    * a shape the scheduler will have to reject onwards.
    *
    * A duration is rejected below zero but allowed at zero -- `CampaignSchedule.scaleAt` handles
    * an empty phase, whereas a negative one puts the phase's end before its start, so it can never
    * match and its negative offset drags every later phase into an overlapping range. Scales are
    * rejected unless finite and non-negative, because `CampaignSchedule.rateAt` collapses any
    * non-positive scale to a rate of 0: a negative one would silently run a phase at no load, and
    * NaN passes that guard entirely and reaches the sampler as the rate itself. Zero is left legal
    * as the one honest way to say a phase generates nothing.
    */
  def validate(phase: CampaignPhaseConfig): Either[String, CampaignPhaseConfig] =
    def scaleOk(field: String, value: Double): Either[String, Unit] =
      if value.isNaN || value.isInfinite then Left(s"campaign phase '${phase.name}' has a non-finite $field")
      else if value < 0.0 then Left(s"campaign phase '${phase.name}' has a negative $field: $value")
      else Right(())

    for
      _ <- Either.cond(
        !phase.duration.isNegative,
        (),
        s"campaign phase '${phase.name}' has a negative duration: ${phase.duration}",
      )
      _ <- (phase.scale, phase.scaleFrom, phase.scaleTo) match
        case (Some(scale), None, None) => scaleOk("scale", scale)
        case (None, Some(from), Some(to)) => scaleOk("scale-from", from).flatMap(_ => scaleOk("scale-to", to))
        case (Some(_), _, _) => Left(s"campaign phase '${phase.name}' sets both scale and scale-from/scale-to")
        case _ => Left(s"campaign phase '${phase.name}' must set either scale, or both scale-from and scale-to")
    yield phase

  given DeriveConfig[CampaignPhaseConfig] = DeriveConfig
    .derived[CampaignPhaseConfig]
    .mapOrFail(phase => validate(phase).left.map(message => Config.Error.InvalidData(message = message)))

case class DiurnalConfig(
    enabled: Boolean,
    peakFactor: Double,
    peakHour: Int,
    timezone: String,
)

case class RegistrationConfig(
    enabled: Boolean,
    target: Long,
    duration: Duration,
)

case class CampaignConfig(
    name: String,
    phases: List[CampaignPhaseConfig],
    diurnal: DiurnalConfig,
    registration: RegistrationConfig,
)

/** One of the ten protected-resource actions of versola-load-emulator-design.md §3: a relative
  * weight, the HTTP method/path against edge, and the ACR it requires (`None` for actions with
  * no step-up requirement).
  */
case class BusinessActionConfig(
    name: String,
    weight: Double,
    method: String,
    path: String,
    acr: Option[String],
)
