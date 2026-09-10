package versola.loadgen.scheduler

import versola.loadgen.config.ActionCountConfig
import versola.loadgen.model.Platform

/** How many business actions one session performs: `NegBinomial(mean 6 mobile / 10 web)` from
  * design doc §2.3, configured by `session.action-count` (versola-loadgen-dev-spec.md §5).
  *
  * Thin by design -- it exists so that the mean/dispersion → platform mapping lives in exactly
  * one place. The distribution's parameterisation, and the fact that it can draw zero, are
  * documented on [[NegBinomial]].
  */
object ActionCount:
  def sample(config: ActionCountConfig, platform: Platform, random: RandomSource): Int =
    NegBinomial.sample(meanFor(config, platform), config.dispersion, random)

  def meanFor(config: ActionCountConfig, platform: Platform): Double =
    platform match
      case Platform.Mobile => config.mobileMean
      case Platform.Web => config.webMean

  def varianceFor(config: ActionCountConfig, platform: Platform): Double =
    NegBinomial.variance(meanFor(config, platform), config.dispersion)
