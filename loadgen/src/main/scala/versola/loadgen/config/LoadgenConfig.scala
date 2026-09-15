package versola.loadgen.config

import versola.util.Secret
import versola.util.postgres.PostgresConfig
import versola.util.postgres.given
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
    session: SessionConfig,
    campaign: CampaignConfig,
    actions: List[BusinessActionConfig],
    provision: Option[ProvisionConfig],
    seed: Option[SeedConfig],
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
    /** Presented as the HTTP Basic password on central's configuration API -- the same base64url
      * value e2e's `RESOURCE_SECRET` carries.
      */
    centralSecret: Config.Secret,
    /** Presented as the HTTP Basic password on edge's service API. */
    edgeSecret: Config.Secret,
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
