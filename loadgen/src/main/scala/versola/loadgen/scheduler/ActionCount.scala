package versola.loadgen.scheduler

import versola.loadgen.config.ActionCountConfig
import versola.loadgen.model.Platform

/** How many business actions one session performs: **one mandatory action plus**
  * `NegBinomial(mean 5 mobile / 9 web)` user-driven ones, configured by `session.action-count`
  * (versola-loadgen-dev-spec.md §5).
  *
  * The split is not cosmetic. Design doc §3 has `GET /accounts` as "the first call of every
  * session", and it is fetched by the app on open rather than chosen by the user -- machine-driven
  * and unconditional, so it was never a draw from the distribution. Modelling the whole session as
  * one NegBinomial forced a choice between two wrong answers: clamp the draw to a minimum of one
  * and move the realised mean off its configured value while distorting precisely the low tail
  * that sets session length, or accept `N = 0` and contradict §3.
  *
  * Taking action #1 out of the draw settles it. The total mean is unchanged at 6.0 / 10.0, §3 is
  * literally true, and the sampler keeps its unbiased low tail -- `P(N' = 0) ≈ 9.9%` at μ = 5,
  * α = 0.6, which is a user who opens the app, glances at their balance and closes it.
  *
  * Thin by design otherwise -- it exists so that the mean/dispersion → platform mapping lives in
  * exactly one place. The distribution's parameterisation is documented on [[NegBinomial]].
  */
object ActionCount:
  /** The mandatory `GET /accounts` the app issues on open, outside the distribution. */
  val mandatoryActions: Int = 1

  def sample(config: ActionCountConfig, platform: Platform, random: RandomSource): Int =
    mandatoryActions + NegBinomial.sample(additionalMeanFor(config, platform), config.dispersion, random)

  /** The configured mean of the *user-driven* actions -- the NegBinomial's own mean, one short of
    * the session's.
    */
  def additionalMeanFor(config: ActionCountConfig, platform: Platform): Double =
    platform match
      case Platform.Mobile => config.mobileMean
      case Platform.Web => config.webMean

  /** What [[sample]] averages to: design doc §2.3's 6.0 mobile / 10.0 web. */
  def sessionMeanFor(config: ActionCountConfig, platform: Platform): Double =
    mandatoryActions + additionalMeanFor(config, platform)

  /** Unchanged by the mandatory action: shifting a distribution by a constant moves its mean and
    * leaves its variance alone.
    */
  def varianceFor(config: ActionCountConfig, platform: Platform): Double =
    NegBinomial.variance(additionalMeanFor(config, platform), config.dispersion)
