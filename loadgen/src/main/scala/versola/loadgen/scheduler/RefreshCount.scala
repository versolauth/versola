package versola.loadgen.scheduler

import versola.loadgen.config.RefreshCountConfig
import versola.loadgen.model.Platform

/** How many refresh exchanges one session adds, and where in the session they happen.
  *
  * The count is `NegBinomial(mean, dispersion)` for mobile and zero for web (see
  * [[RefreshCountConfig]]). Each exchange is placed before one of the session's actions, uniformly
  * and independently, so several can land before the same action (an app that refreshes twice in a
  * row after a long background); the placement is drawn here, with the rest of the plan, rather
  * than flipped per action, for the reason [[versola.loadgen.scenario.SessionPlan]] gives.
  */
object RefreshCount:

  def sample(config: RefreshCountConfig, platform: Platform, random: RandomSource): Int =
    platform match
      case Platform.Mobile if config.mobileMean > 0.0 => NegBinomial.sample(config.mobileMean, config.dispersion, random)
      case _ => 0

  /** `slots(i)` is how many refreshes happen before action `i`; always `actionCount` long. */
  def place(count: Int, actionCount: Int, random: RandomSource): Vector[Int] =
    if actionCount <= 0 then Vector.empty
    else
      val slots = Array.fill(actionCount)(0)
      var drawn = 0
      while drawn < count do
        slots(math.min(actionCount - 1, (random.nextDouble() * actionCount).toInt)) += 1
        drawn += 1
      slots.toVector
