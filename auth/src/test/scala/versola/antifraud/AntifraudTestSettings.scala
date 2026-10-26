package versola.antifraud

import versola.util.http.VersolaApp
import zio.*
import zio.config.magnolia.deriveConfig

/** The settings as `versola-defaults.conf` has them (everything off), read the way `auth` reads them, for specs to
  * `copy` the keys they care about from. Case classes of settings have no defaults of their own. */
object AntifraudTestSettings:

  private def load[A](config: Config[A]): A =
    Unsafe.unsafe: unsafe ?=>
      Runtime.default.unsafe.run(AntifraudConfigSpec.load("", None, config)).getOrThrowFiberFailure()

  val events: AntifraudEvents.Settings = load(Config.Nested("antifraud", deriveConfig[AntifraudEvents.Settings]))
  val lists: AntifraudLists.Settings = load(Config.Nested("antifraud", Config.Nested("lists", deriveConfig[AntifraudLists.Settings])))
  val detectors: AntifraudDetectors.Settings =
    load(Config.Nested("antifraud", Config.Nested("detectors", deriveConfig[AntifraudDetectors.Settings])))

  val smsBudget: SmsBudget.Settings = events.smsBudget
  val decision: Antifraud.Settings = events.decision
  val velocity: VelocityCounters.Settings = events.velocity
  val thresholds: VelocityRules.Thresholds = events.velocity.thresholds

  /** A limit that is not set: no window limited, the weight a limit of a single key has in most specs. */
  val limit: VelocityRules.Limit = VelocityRules.Limit(perMinute = 0, perTenMinutes = 0, perHour = 0, perDay = 0, weight = 50)

  val velocityOff: VelocityCounters = VelocityCounters(velocity)

  /** A tuning that is never stored: what `tuning` says applies everywhere. */
  final class StaticTuning(val tuning: Tuning) extends AntifraudTuning:
    override def effective(tenant: Option[versola.oauth.client.model.TenantId]): Tuning = tuning

    override def smsLimitsEnabled: Boolean = tuning.smsLimits.enabled

    override def stored(tenant: Option[versola.oauth.client.model.TenantId]): Task[Option[TuningPatch]] = ZIO.none

    override def put(tenant: Option[versola.oauth.client.model.TenantId], patch: TuningPatch, actor: String): Task[Unit] =
      ZIO.dieMessage("not stored")

    override def delete(tenant: Option[versola.oauth.client.model.TenantId], actor: String): Task[Boolean] = ZIO.dieMessage("not stored")

    override def audit(scope: Option[Option[versola.oauth.client.model.TenantId]], limit: Int): Task[List[AntifraudTuningAudit]] =
      ZIO.dieMessage("not stored")

  /** The tuning the config gives, with the thresholds of `decision` and the limits of `velocity`. */
  def tuning(decision: Antifraud.Settings, velocity: VelocityCounters.Settings = velocity): StaticTuning =
    StaticTuning(AntifraudTuning.fromSettings(events.copy(decision = decision, velocity = velocity)))

  def engine(decision: Antifraud.Settings, ruleSets: List[Antifraud.RuleSet], velocity: VelocityCounters.Settings = velocity): Antifraud.Impl =
    Antifraud.Impl(decision, ruleSets, tuning(decision, velocity))

  /** The velocity rules for limits of `velocity`. */
  def velocityRules(velocity: VelocityCounters.Settings): Antifraud.RuleSet =
    VelocityRules.ruleSet(velocity, tuning(decision, velocity))
