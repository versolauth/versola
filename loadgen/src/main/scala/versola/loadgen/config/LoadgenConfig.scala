package versola.loadgen.config

import versola.util.postgres.PostgresConfig
import versola.util.postgres.given
import versola.util.{Dpop, Secret}
import zio.Config
import zio.Duration
import zio.config.magnolia.DeriveConfig

/** Root configuration tree for `loadgen`, decoded from HOCON via `VersolaApp.parseConfig`
  * (see versola-loadgen-dev-spec.md §5). One process, one file, one binary: `role` picks which
  * half runs; `shard` is present only when `role = driver`.
  *
  * Deviates from the dev spec in two respects, both on `session.actions`:
  *   - Renamed to `session.action-count` / [[ActionCountConfig]]. The spec's HOCON sketch uses
  *     `actions` for two unrelated things -- this distribution and, separately, a top-level
  *     `actions` list of the ten business action definitions -- and the two would collide under
  *     one key.
  *   - Its `mobile-mean`/`web-mean` are the *user-driven* actions only, one short of the spec's
  *     6.0/10.0 session mean (5.0/9.0), because [[versola.loadgen.scheduler.ActionCount]] adds
  *     the app's own opening call outside the draw. See that object's doc for why. A config file
  *     written against §5's literal example (6/10) decodes without error but *overstates* the
  *     session mean by one action -- the opening call is added on top, so 6/10 here run sessions
  *     of 7.0/11.0 actions. The dev spec's own worked example needs updating to 5/9 to match.
  */
case class LoadgenConfig(
    role: LoadgenRole,
    shard: Option[ShardConfig],
    targets: TargetsConfig,
    coordinator: CoordinatorClientConfig,
    store: StoreConfig,
    population: PopulationConfig,
    clients: Option[ClientsConfig],
    session: SessionConfig,
    campaign: CampaignConfig,
    actions: List[BusinessActionConfig],
    plan: Option[PlanConfig],
    provision: Option[ProvisionConfig],
    seed: Option[SeedConfig],
    calibration: Option[CalibrationConfig],
    sutStats: Option[SutStatsConfig],
    poolerStats: Option[PoolerStatsConfig],
    dpop: Option[DpopConfig],
)

/** Drives the campaign with RFC 9449 sender-constrained tokens instead of bearer ones.
  *
  * The block's *presence* is the switch, as it is for [[SutStatsConfig]] and
  * [[PoolerStatsConfig]]: there is no `enabled` flag to disagree with the settings under it. Read
  * by the driver, which does the proving, and by the coordinator, which only needs to know which
  * mode the report should say the run was driven in -- so both roles must be given the same
  * block or the report describes a run that did not happen.
  *
  * @param keyPoolSize
  *   how many client keys the whole fleet shares. A key per virtual user is the faithful shape
  *   and buys nothing the SUT can distinguish -- see [[versola.loadgen.protocol.DpopKeyPool]],
  *   which argues the case and is where the number is spent.
  * @param keySeed
  *   what the pool is derived from. Must be identical across the fleet and stable across a
  *   restart, or a resumed session's refresh is refused against the `cnf.jkt` its token was
  *   bound to; `DpopKeyPool` explains why that failure is worse than it sounds. Not a secret:
  *   these keys authenticate emulated users against a test deployment, and reproducibility is
  *   the property being bought.
  * @param algorithm
  *   which RFC 9449 signing algorithm the pool's keys use, `ES256` when absent. Must name one
  *   the deployment's served `dpop_signing_alg_values_supported` actually includes -- a
  *   deployment that restricted that set to exclude `ES256` (a FAPI 2.0 profile requiring
  *   `PS256`, say) has every proof this driver makes refused as `invalid_dpop_proof` under the
  *   default, and [[versola.loadgen.protocol.DpopKeyPool]] explains why that reads as the SUT
  *   failing outright rather than as a config mismatch. `PS256`'s RSA-2048 keygen is roughly
  *   three orders of magnitude slower than `ES256`'s P-256 -- tens of milliseconds a key,
  *   noticeable at boot for a large pool but paid once for the fleet either way.
  */
case class DpopConfig(keyPoolSize: Int, keySeed: String, algorithm: Option[Dpop.Algorithm])

object DpopConfig:
  // Same idiom as LoadgenRole: a plain string in HOCON (`ES256`/`PS256`), not
  // zio-config-magnolia's sealed-trait coproduct shape. `RS256` parses -- `Dpop.Algorithm` names
  // it -- but `DpopKeyPool.derive` refuses it, since no compliant deployment serves it; failing
  // that at derive time rather than here keeps this decoder a mirror of the enum, not a second
  // place enumerating which members are actually drivable.
  given DeriveConfig[Dpop.Algorithm] = DeriveConfig[String]
    .mapOrFail: str =>
      Dpop.Algorithm.fromName(str).toRight(Config.Error.InvalidData(message = s"unknown DPoP algorithm: $str"))

  given DeriveConfig[DpopConfig] = DeriveConfig.derived

/** Which half of the `loadgen` binary this process runs. Same binary and image serve all five --
  * see versola-loadgen-dev-spec.md §12 (coordinator) and §7 (driver). `Seed`/`Provision` are the
  * one-shot `loadgen seed` / `loadgen provision` subcommands (§10, §4's `AdminClient`), and
  * `Calibrate` is the one-shot calibration gate of §9/§13 (versolauth/versola#281).
  */
enum LoadgenRole(val configValue: String):
  case Coordinator extends LoadgenRole("coordinator")
  case Driver extends LoadgenRole("driver")
  case Seed extends LoadgenRole("seed")
  case Provision extends LoadgenRole("provision")
  case Calibrate extends LoadgenRole("calibrate")

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
    mockUrl: String,
    /** WebAuthn `rp.origin` the software authenticator signs against (§4, §8.3). */
    origin: String,
)

case class CoordinatorClientConfig(url: String, pollInterval: Duration)

/** Which provisioned clients a driver authenticates its virtual users as (design doc §2.2).
  *
  * Named here rather than taken from [[versola.loadgen.provision.CampaignBlueprint]]'s constants,
  * even though those are the ids `loadgen provision` writes: a driver never runs that role and
  * its config file omits the block entirely, so reading the blueprint would have a driver
  * authenticate against the campaign's *intended* configuration in place of the one it was
  * actually pointed at. A campaign run against a central someone else provisioned is exactly the
  * case that has to be expressible.
  *
  * Optional for the same reason [[ShardConfig]] is: no other role logs anyone in, and requiring
  * the block would fail a coordinator's or a seeder's decode before `role` was ever read. Role
  * dispatch asserts it is present for `driver`.
  *
  * @param mobileRedirectUri
  *   the app scheme the three mobile clients' authorization codes come back on. One value for
  *   all three because `provision` registers one.
  * @param scope
  *   requested on every authorization. `offline_access` in particular is what makes §2.3's 96.7%
  *   refresh path exist at all -- a scope string without it produces a campaign of full logins
  *   that still passes every threshold while measuring the wrong thing.
  * @param otpLength
  *   how many digits the non-prod OTP has. Stated rather than fixed at six because it is a
  *   property of the tenant's `challenge_settings` the driver has to match, and a mismatch fails
  *   every login in the campaign at the same step.
  */
case class ClientsConfig(
    mobileOtp: String,
    mobileOtpPassword: String,
    mobilePasskey: String,
    mobileRedirectUri: String,
    webPreset: String,
    scope: String,
    otpLength: Int,
)

case class WriteBehindConfig(flushInterval: Duration, batchSize: Int)

/** The emulator's own Postgres -- its bookkeeping, never the SUT's (§6).
  *
  * `postgres` is `util-postgres`' own [[versola.util.postgres.PostgresConfig]] rather than the
  * `url` + `maximum-pool-size` pair dev spec §5 sketches, which could not build a pool at all:
  * no user, no password, no timeouts. Reusing the full block also reuses its pool-tuning
  * validation and its `Secret`-typed password, and `PostgresHikariDataSource.transactor` reads
  * it straight out of `store.postgres` via that function's `configPath`.
  *
  * The block is nested here rather than being the ambient top-level `postgres { }` precisely
  * because the seeder role (§10) writes into a *second* database -- the SUT's -- and one
  * unnamed block cannot name both.
  */
case class StoreConfig(
    postgres: PostgresConfig,
    writeBehind: WriteBehindConfig,
)

object StoreConfig:
  /** Anchored in the companion rather than left to the use site: deriving this needs
    * `DeriveConfig[Secret]` for the password, which `versola.util.postgres` declares top-level
    * and which is therefore *not* in scope wherever `deriveConfig[LoadgenConfig]` happens to be
    * called. Here it is, so every caller gets the derivation without knowing that.
    */
  given DeriveConfig[StoreConfig] = DeriveConfig.derived

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

/** @param refreshTokenTtl
  *   how long a rotated refresh token stays usable. Not in dev spec §5, and required rather than
  *   inferred: the token response carries `expires_in` for the *access* token only, while
  *   `vu_sessions.refresh_expires_at` is what `listLive` resumes a mobile session on after a
  *   restart. Carrying the previous row's expiry forward instead would make every rotation
  *   shorten the session's remaining life, and guessing it long would have a driver resume on
  *   tokens the SUT has already expired and report the 401s as the SUT's. It has to match the
  *   client's provisioned `refreshTokenTtlSeconds`
  *   ([[versola.loadgen.provision.CampaignBlueprint]]'s 30 days).
  */
case class SessionConfig(
    fullLoginProbability: FullLoginProbabilityConfig,
    actionCount: ActionCountConfig,
    thinkTime: ThinkTimeConfig,
    paymentProbability: Double,
    extraRefreshProbability: Double,
    logoutProbability: LogoutProbabilityConfig,
    accessTokenTtl: Duration,
    refreshTokenTtl: Duration,
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

/** What `role = coordinator` needs beyond the blocks every role shares in order to publish a
  * plan (versola-loadgen-dev-spec.md §12). Optional for the same reason [[ShardConfig]],
  * [[ProvisionConfig]] and [[SeedConfig]] are: no other role publishes a plan, and requiring the
  * block here would fail their decode before role dispatch ever read `role`.
  *
  * @param shardCount
  *   the shard map the campaign starts on. Not read from [[ShardConfig]], which a coordinator
  *   process does not have, and not derived from `max(vu_users.shard)`, which is empty for the
  *   registration campaign -- the one campaign that starts with no population at all. A driver's
  *   `shard.count` is its bootstrap value only: after the first poll the coordinator's map is
  *   authoritative, because re-sharding changes it mid-campaign and the drivers' files do not.
  * @param acceptance
  *   which recorded measurements the design doc's acceptance thresholds are about
  */
case class PlanConfig(shardCount: Int, acceptance: AcceptanceMeasurementsConfig)

object PlanConfig:
  /** Same idiom as [[SeedConfig.validate]]'s. A non-positive shard count is rejected here because
    * the alternative is a campaign that boots: `ShardAssignment.shardOf` requires a positive
    * count, so the first `/plan` poll would answer 500 to every driver, and a count of zero also
    * makes `ArrivalProcess.shardRate` divide by zero rather than fail.
    */
  def validate(config: PlanConfig): Either[String, PlanConfig] =
    for
      _ <- Either.cond(config.shardCount > 0, (), s"plan.shard-count must be positive, got ${config.shardCount}")
      // A campaign with no latency ceiling produces a verdict in which nothing about the SUT's
      // speed was judged -- `passed = true` on a run that measured latency and graded none of it.
      _ <- Either.cond(
        config.acceptance.latency.nonEmpty,
        (),
        "plan.acceptance.latency states no thresholds, so no endpoint's latency would be judged",
      )
      _ <- config.acceptance.latency.find(_.ceiling.toNanos <= 0L) match
        case Some(threshold) =>
          Left(s"plan.acceptance.latency entry '${threshold.name}' has a non-positive ceiling: ${threshold.ceiling}")
        case None => Right(())
      // Two entries for one measurement produce two identically named checks, and `passed` would
      // then depend on which ceiling the reader happened to look at.
      _ <- config.acceptance.latency.groupBy(threshold => (threshold.scenario, threshold.name)).find(_._2.sizeIs > 1) match
        case Some(((scenario, name), _)) =>
          Left(s"plan.acceptance.latency names ${scenario.fold(name)(step => s"$step/$name")} more than once")
        case None => Right(())
    yield config

  given DeriveConfig[PlanConfig] = DeriveConfig
    .derived[PlanConfig]
    .mapOrFail(config => validate(config).left.map(message => Config.Error.InvalidData(message = message)))

/** One recorded measurement, named the way `versola.loadgen.metrics.MeasurementId` names it: a
  * step belongs to a scenario, a flow is named on its own, so `scenario = None` means a flow.
  */
case class MeasurementRefConfig(scenario: Option[String], name: String)

/** One endpoint's absolute latency ceiling: §6's table states a different one per endpoint, so
  * the measurement and its ceiling are stated together rather than the measurement being named
  * here and its ceiling fixed in code.
  *
  * `ceiling` is a p99, as every latency criterion in §6.7 is; it is not called `p99` because the
  * kebab-case provider renders that field name as `p-99`, which in a HOCON file reads as a typo.
  */
case class LatencyThresholdConfig(scenario: Option[String], name: String, ceiling: Duration)

/** Which measurements the campaign is judged on, and at what ceiling.
  *
  * In configuration rather than as constants in the coordinator because the scenario and step
  * names belong to the scenario engine: a threshold naming a measurement nothing records is
  * reported as *not evaluated* by `CampaignReport`, so a hard-coded guess at the naming would
  * turn the campaign's headline criteria into lines nobody checked.
  *
  * `latency` is a list because a verdict that only grades `/token` leaves §6's "assessment"
  * column empty for every other endpoint the campaign drove. The relative criterion stays a pair
  * of named measurements: a campaign says which two hops it is comparing, but how much overhead
  * an edge hop may add is the design doc's 15 ms, not a knob
  * ([[versola.loadgen.metrics.AcceptanceThresholds.edgeProxyMargin]]).
  */
case class AcceptanceMeasurementsConfig(
    latency: List[LatencyThresholdConfig],
    edgeProxy: MeasurementRefConfig,
    mockBackend: MeasurementRefConfig,
)

/** What `loadgen provision` needs beyond [[TargetsConfig]] to write the campaign's configuration
  * into central (versola-load-emulator-design.md §3). Optional for the same reason [[ShardConfig]]
  * is: a driver or coordinator process holds no admin credentials and its config file omits the
  * block entirely, which requiring it here would turn into a decode failure before role dispatch
  * ever read `role`.
  *
  * Only what a deployment actually varies lives here. The campaign's own shape -- which clients
  * exist, the ten endpoints, the permissions and the two roles -- is fixed by the design doc and
  * lives in [[versola.loadgen.provision.CampaignBlueprint]], not in HOCON: it is the definition of
  * the campaign, not a knob.
  *
  * @param paymentAmountThreshold the amount above which the payment endpoints' CEL access rule
  *                               denies, in the minor unit the campaign's bodies carry
  */
case class ProvisionConfig(
    tenantId: String,
    /** The `client_credentials` client every admin call is made as. Central seeds it from its own
      * `bootstrap.utility-client` block, which is where this secret is configured to match;
      * loadgen holds no internal secret of central's or edge's, and reaches the admin API only
      * through edge's proxy, with the permissions that client was granted.
      */
    provisionerClientId: String,
    provisionerSecret: Config.Secret,
    /** Where the three mobile clients' authorization codes are redirected -- an app scheme, which
      * central accepts for a native client but not over plain HTTP on a non-loopback host.
      */
    mobileRedirectUri: String,
    resources: ProvisionResourcesConfig,
    preset: ProvisionPresetConfig,
    passkey: ProvisionPasskeyConfig,
    paymentAmountThreshold: Long,
)

/** The three `mockapi`-backed resources' RFC 8707 identifiers, which are also the base URIs edge
  * proxies to. One per resource rather than derived from `targets.mock-url`: central requires each
  * to be absolute and path-less, so three resources on one `mockapi` need three authorities
  * resolving to it, which only the deployment knows.
  */
case class ProvisionResourcesConfig(coreUri: String, payUri: String, notifyUri: String)

/** One of the system under test's databases, as `loadgen seed` reaches it (§10).
  *
  * Not [[versola.util.postgres.PostgresConfig]], which is the house type, and for a stated
  * reason: seven of its nine fields tune a HikariCP pool, and the seeder holds exactly one
  * connection per database -- `COPY` is serial per table and the seeder's parallelism is in
  * Argon2, not in the database (see [[versola.loadgen.seed.CopySink.OfConnection]]). Reusing the
  * block would present `maximum-pool-size` and four timeouts as knobs that do nothing, which is
  * worse than a smaller type. The emulator's *own* store keeps `PostgresConfig`, because that one
  * really is pooled.
  */
case class SutDatabaseConfig(url: String, user: String, password: Config.Secret)

/** What `loadgen seed` needs beyond [[PopulationConfig]] to bulk-populate the SUT (§10).
  * Optional for the same reason [[ShardConfig]] and [[ProvisionConfig]] are: a driver holds no
  * SUT database credentials and its config file omits the block, which requiring it here would
  * turn into a decode failure before role dispatch read `role`.
  *
  * How many users to write is [[PopulationConfig.target]], and the mix is the rest of that block,
  * so neither is restated here -- the seeder and the drivers have to be reading one population
  * definition, not two.
  *
  * @param passwordsSecret
  *   auth's Argon2 pepper, which enters the hash as `additional` data. The *same* base64url
  *   string auth is given as `PASSWORDS_SECRET`, decoded the same way (16 bytes), because a
  *   seeder with a different pepper produces a population whose every password is individually
  *   well-formed and none of which verify -- and nothing in that failure points here.
  * @param shardCount
  *   how many drivers the campaign will run, because `shard = id % shardCount` is denormalised
  *   into `vu_users.shard` at write time (§7.1). A seed process owns no shard, so it cannot come
  *   from [[ShardConfig]]; re-sharding later is the bulk `UPDATE` at a phase boundary the
  *   tracking issue describes, not something the seeder can leave to the drivers.
  * @param hashParallelism
  *   concurrent Argon2id hashes. Each holds ~19 MiB of heap, so this is the knob that bounds the
  *   seeder's hashing footprint -- and it is required rather than "all cores" precisely because
  *   the number of cores is not the constraint the heap is.
  * @param batchSize
  *   users per `COPY` and per transaction. Also the granularity a crashed run resumes at.
  */
case class SeedConfig(
    auth: SutDatabaseConfig,
    central: SutDatabaseConfig,
    tenantId: String,
    passwordsSecret: Secret.Bytes16,
    shardCount: Int,
    hashParallelism: Int,
    batchSize: Int,
)

object SeedConfig:
  /** Same idiom as [[CampaignPhaseConfig.validate]]'s: reject at decode time rather than hand the
    * seeder a number it cannot act on.
    *
    * Both are counts that fail silently rather than loudly when non-positive.
    * `hash-parallelism` is the worse of them: it is handed to `Argon2Config.maxConcurrent`, so a
    * zero builds a zero-permit semaphore inside `SecurityService` and every password hash then
    * waits on it forever -- `role = seed` hangs rather than failing, and it hangs at the first
    * batch containing a password user, well after two SUT connections and a migration.
    * `batch-size` at zero makes `Seeder.batches` iterate the same id forever; that one has its
    * own `require`, but it fires at the same late point, so it is hoisted here too.
    *
    * `shard-count` is deliberately absent: `PopulationPlan.validate` already rejects it, and one
    * rule stated twice is one rule that can come to disagree with itself.
    */
  def validate(config: SeedConfig): Either[String, SeedConfig] =
    def positive(field: String, value: Int): Either[String, Unit] =
      Either.cond(value > 0, (), s"seed.$field must be positive, got $value")

    for
      _ <- positive("hash-parallelism", config.hashParallelism)
      _ <- positive("batch-size", config.batchSize)
    yield config

  /** Anchored in the companion for the same reason [[StoreConfig]]'s is: deriving this needs a
    * `DeriveConfig` for `Secret.Bytes16` and one for `Config.Secret`, neither of which is in
    * scope wherever `deriveConfig[LoadgenConfig]` is called.
    *
    * The `Secret.Bytes16` derivation mirrors `PostgresOAuthApp`'s exactly -- base64url, length
    * checked -- rather than `versola.util.postgres`' `Secret.fromString`, because this value has
    * to round-trip the same string auth reads. Silently interpreting auth's base64url pepper as
    * raw UTF-8 bytes is the single most likely way to get a population that cannot log in, and
    * it would not fail anywhere near here.
    */
  given DeriveConfig[Secret.Bytes16] = DeriveConfig[String]
    .mapOrFail: value =>
      Secret.Bytes16
        .fromBase64Url(value)
        .left.map(message => Config.Error.InvalidData(message = message))
        .filterOrElse(
          _.length == 16,
          Config.Error.InvalidData(message = "seed.passwords-secret must be 16 base64url-encoded bytes"),
        )

  given DeriveConfig[SeedConfig] = DeriveConfig
    .derived[SeedConfig]
    .mapOrFail(config => validate(config).left.map(message => Config.Error.InvalidData(message = message)))

/** One system-under-test database the coordinator takes `pg_stat_*` snapshots of, under the name
  * the report shows it by (runbook 05-report-spec.md §3: "по одному блоку на каждую базу, раз
  * базы разделены по сервисам").
  *
  * A list of named databases rather than the fixed `auth`/`central` pair [[SeedConfig]] has,
  * because the two blocks answer different questions. The seeder writes a population into the two
  * schemas it knows the shape of; this one only reads statistics, which every database answers
  * identically, and the topology it is pointed at is the deployment's to state -- three instances
  * at campaign scale (runbook 03-postgres-topology.md), one combined instance on a developer's
  * machine.
  */
case class SutStatsDatabaseConfig(name: String, database: SutDatabaseConfig)

/** What a coordinator needs to answer §3 of the report: credentials to the SUT's databases, which
  * no role but `seed` has held until now.
  *
  * Optional for the same reason [[ShardConfig]], [[ProvisionConfig]] and [[SeedConfig]] are, and
  * with one addition: a coordinator whose config file omits the block is a supported deployment,
  * not a misconfigured one. Reading another service's database is a privilege somebody has to
  * grant, and a campaign whose report is missing its database section is still a campaign; a
  * coordinator that refused to boot without it would make every other section hostage to that
  * grant.
  */
case class SutStatsConfig(databases: List[SutStatsDatabaseConfig])

object SutStatsConfig:
  /** Configured databases grouped by the Postgres cluster their URL names, kept only where a
    * group has more than one member -- runbook 03-postgres-topology.md's "три инстанса на проде,
    * один совмещённый инстанс у разработчика" is exactly the shape that produces one.
    *
    * `pg_stat_wal`, `pg_stat_checkpointer` and `pg_stat_io` answer for the whole cluster, unlike
    * every other view this reads, which [[versola.loadgen.sut.SutStatsReader]] already scopes to
    * `current_database()`. Two configured databases on the same cluster therefore read those
    * three identically and report them under two names -- correct for each name alone, but a
    * downstream sum across the report's database sections would count that activity twice. This
    * is read at boot only, to warn about it; the capture itself does not act on it; deleting a
    * name's own copy would need to know which name's copy is the coordinator's story and there is
    * no such name -- both are equally the cluster's.
    */
  def clusterGroups(databases: List[SutStatsDatabaseConfig]): List[List[String]] =
    databases
      .groupBy(target => clusterKey(target.database.url))
      .values
      .map(_.map(_.name))
      .filter(_.size > 1)
      .toList
      .sortBy(_.head)

  /** The URL's host and port, which is what makes two JDBC URLs the same Postgres cluster
    * regardless of which database each names. Falls back to the whole URL for one this cannot
    * parse, which undercounts rather than overcounts: two unparsed URLs then compare unequal
    * even if they are in fact the same cluster, so this only ever fails to warn, never warns
    * about two clusters that do not share one.
    */
  private def clusterKey(url: String): String =
    scala.util.Try(java.net.URI.create(url.stripPrefix("jdbc:")).getAuthority).toOption.flatMap(Option(_)).getOrElse(url)

  /** Same idiom as [[SeedConfig.validate]]'s, and the same reason: both failures are silent where
    * they land. An empty list produces a coordinator that boots with the block, logs nothing and
    * reports no database section -- indistinguishable from one configured without the block at
    * all. Duplicate names produce two snapshots competing for one `(campaign, database, phase)`
    * row, where the second is dropped by the identity index and the report shows one database's
    * statistics under another's name.
    */
  def validate(config: SutStatsConfig): Either[String, SutStatsConfig] =
    val names = config.databases.map(_.name)
    for
      _ <- Either.cond(config.databases.nonEmpty, (), "sut-stats.databases must name at least one database")
      _ <- Either.cond(names.forall(_.nonEmpty), (), "sut-stats.databases[].name must not be empty")
      _ <- Either.cond(
        names.distinct.size == names.size,
        (),
        s"sut-stats.databases[].name must be unique, got ${names.mkString(", ")}",
      )
    yield config

  /** Anchored in the companion for [[StoreConfig]]'s reason: deriving this reaches
    * [[SutDatabaseConfig]]'s `Config.Secret` password, whose derivation is in scope here and not
    * wherever `deriveConfig[LoadgenConfig]` is called.
    */
  given DeriveConfig[SutStatsConfig] = DeriveConfig
    .derived[SutStatsConfig]
    .mapOrFail(config => validate(config).left.map(message => Config.Error.InvalidData(message = message)))

/** One PgBouncer the coordinator reads the admin console of, under the name the report shows it
  * by (runbook 05-report-spec.md §4).
  *
  * @param admin
  *   the admin console, which is a [[SutDatabaseConfig]] because it is reached exactly like a
  *   database: a JDBC URL naming PgBouncer's virtual `pgbouncer` database on its listen port, and
  *   a user in `admin_users` or `stats_users`. `stats_users` is enough for everything
  *   [[versola.loadgen.sut.PoolerStatsReader]] needs except `SHOW CONFIG`, which it degrades over.
  */
case class PoolerConfig(name: String, admin: SutDatabaseConfig)

/** What a coordinator needs to answer §4's pooler half. Optional for [[SutStatsConfig]]'s reason,
  * and independently of it: 04-pgbouncer.md installs the pooler before campaign 1, but a
  * developer's stack runs without one and its campaigns are still campaigns.
  */
case class PoolerStatsConfig(poolers: List[PoolerConfig])

object PoolerStatsConfig:
  /** [[SutStatsConfig.validate]]'s rules, for [[SutStatsConfig.validate]]'s reasons: an empty list
    * is indistinguishable from an omitted block, and duplicate names collide on the identity index
    * of V0006 so that one pooler's statistics appear under another's name.
    */
  def validate(config: PoolerStatsConfig): Either[String, PoolerStatsConfig] =
    val names = config.poolers.map(_.name)
    for
      _ <- Either.cond(config.poolers.nonEmpty, (), "pooler-stats.poolers must name at least one pooler")
      _ <- Either.cond(names.forall(_.nonEmpty), (), "pooler-stats.poolers[].name must not be empty")
      _ <- Either.cond(
        names.distinct.size == names.size,
        (),
        s"pooler-stats.poolers[].name must be unique, got ${names.mkString(", ")}",
      )
    yield config

  /** Anchored in the companion for [[SutStatsConfig]]'s reason -- the same `Config.Secret`
    * password is reached through [[PoolerConfig.admin]].
    */
  given DeriveConfig[PoolerStatsConfig] = DeriveConfig
    .derived[PoolerStatsConfig]
    .mapOrFail(config => validate(config).left.map(message => Config.Error.InvalidData(message = message)))

/** The one edge login preset, for the `web-otp` client (design doc §2.2). `cookieDomain`/
  * `cookiePath` scope the `EDGE_SESSION` cookie; both are optional in central, so both are
  * `Option` here rather than silently defaulted to the whole origin.
  */
case class ProvisionPresetConfig(
    id: String,
    cookieDomain: Option[String],
    cookiePath: Option[String],
    postLogoutRedirectUri: Option[String],
)

/** WebAuthn relying-party settings for the tenant. `rpId` is a registrable domain suffix of
  * `targets.origin`, which the authenticator signs against, so it cannot be derived from the
  * origin URL without guessing where the site's boundary is.
  */
case class ProvisionPasskeyConfig(rpId: String, rpName: String, userVerification: String)

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

/** What `mockapi`'s `DelaySampler` is configured to produce for one delay profile, as the
  * calibration gate states it (versolauth/versola#281: "the driver's measured p50/p99 must match
  * the sampler's *configured* p50/p99 within 2 ms").
  *
  * In configuration rather than as constants in this tree, for a stated reason: `mockapi` is a
  * separate sbt module that `loadgen` deliberately does not depend on (see build.sbt's comment on
  * the `mockapi` project), so a copy of `DelaySampler.readTargets`/`writeTargets` here would be a
  * second statement of the same two pairs of numbers, free to drift from the process actually
  * serving them -- and a calibration that compares a driver against a stale copy of the backend's
  * configuration is worse than no calibration. The deployment that runs both knows which figures
  * the backend was built with.
  *
  * The *tolerance* is deliberately not here: it is the gate's own figure and lives in
  * [[versola.loadgen.calibrate.CalibrationVerdict]], for the same reason
  * [[AcceptanceMeasurementsConfig]] names measurements but not thresholds.
  *
  * Written `p-50`/`p-99` in the file rather than `p50`/`p99`: `VersolaApp.parseConfig` reads this
  * tree through a kebab-case provider, which splits a field name before a digit exactly as it
  * splits one before a capital.
  */
case class CalibrationTargetsConfig(p50: Duration, p99: Duration)

/** What `role = calibrate` needs beyond the blocks every role shares (versolauth/versola#281).
  *
  * Optional for the same reason [[ShardConfig]], [[ProvisionConfig]] and [[SeedConfig]] are: no
  * other role runs the gate, and requiring the block here would fail their decode before role
  * dispatch ever read `role`.
  *
  * The run's *shape* is ordinary campaign config and is not restated here: how long it runs and at
  * what multiple of `rate-per-second` is `campaign.phases`, and which calls it makes is the
  * top-level `actions` list. [[versola.loadgen.calibrate.Calibration]] rejects the campaign shapes
  * that would make the rate vary, since a gate run at an unknown rate measures nothing.
  *
  * @param ratePerSecond
  *   the known fixed arrival rate the gate is run at, in arrivals per second, before
  *   `campaign.phases[].scale`. Required and with no default: "a 30-minute run at a known fixed
  *   rate" is the whole premise, and a rate this process picked for itself would not be known.
  * @param seed
  *   the campaign seed the arrival process and the action draw run off, so a failed gate can be
  *   re-run on the same stream ([[versola.loadgen.scheduler.RandomSource]] takes no other kind of
  *   seed on purpose).
  */
case class CalibrationConfig(
    ratePerSecond: Double,
    seed: Long,
    read: CalibrationTargetsConfig,
    write: CalibrationTargetsConfig,
)

object CalibrationConfig:
  /** Same idiom as [[SeedConfig.validate]]'s: reject at decode time rather than hand the gate a
    * number it cannot act on.
    *
    * A non-positive or non-finite rate is the worse case: `ArrivalProcess.next` requires a
    * positive ceiling and dies on anything else, so the run would fail some way into its own
    * startup rather than at boot. Non-positive configured quantiles are rejected because a gate
    * that compares a measurement against a target of zero reports a delta of the measurement
    * itself -- a failure whose message points at the driver and not at the config file.
    */
  def validate(config: CalibrationConfig): Either[String, CalibrationConfig] =
    def positiveRate: Either[String, Unit] =
      if config.ratePerSecond.isNaN || config.ratePerSecond.isInfinite then
        Left(s"calibration.rate-per-second must be finite, got ${config.ratePerSecond}")
      else Either.cond(config.ratePerSecond > 0.0, (), s"calibration.rate-per-second must be positive, got ${config.ratePerSecond}")

    def positiveTargets(profile: String, targets: CalibrationTargetsConfig): Either[String, Unit] =
      for
        _ <- Either.cond(targets.p50.toNanos > 0L, (), s"calibration.$profile.p50 must be positive, got ${targets.p50}")
        _ <- Either.cond(targets.p99.toNanos > 0L, (), s"calibration.$profile.p99 must be positive, got ${targets.p99}")
        _ <- Either.cond(
          targets.p99.toNanos >= targets.p50.toNanos,
          (),
          s"calibration.$profile.p99 (${targets.p99}) must not be below its p50 (${targets.p50})",
        )
      yield ()

    for
      _ <- positiveRate
      _ <- positiveTargets("read", config.read)
      _ <- positiveTargets("write", config.write)
    yield config

  given DeriveConfig[CalibrationConfig] = DeriveConfig
    .derived[CalibrationConfig]
    .mapOrFail(config => validate(config).left.map(message => Config.Error.InvalidData(message = message)))
