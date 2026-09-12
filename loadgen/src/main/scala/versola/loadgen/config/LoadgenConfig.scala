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
    provision: Option[ProvisionConfig],
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

/** One phase of the campaign's arrival-rate envelope (§7.3). `scale` is used by flat phases
  * (`warmup`, `steady`, `spike`, `recover`); `scaleFrom`/`scaleTo` by the linear `ramp` phase.
  * All three are optional and mutually informative rather than mutually exclusive in the
  * schema -- the scheduler (track D) decides which apply per phase `name`.
  */
case class CampaignPhaseConfig(
    name: String,
    duration: Duration,
    scale: Option[Double],
    scaleFrom: Option[Double],
    scaleTo: Option[Double],
)

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
