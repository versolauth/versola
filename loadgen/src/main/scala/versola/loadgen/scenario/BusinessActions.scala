package versola.loadgen.scenario

import versola.loadgen.config.BusinessActionConfig
import versola.loadgen.protocol.ActionCall
import versola.loadgen.scheduler.RandomSource
import zio.http.Method

/** The ten protected actions of design doc §3 as a driver draws from them (§5's `actions` list,
  * §8.6).
  *
  * Three different draws, because §2.3 asks three different questions of the same list: the call
  * the app makes on open, the ordinary weighted pick for every user-driven slot, and the one
  * action that requires L2 when the session's payment draw came up. Folding the last two together
  * -- picking by weight and hoping a payment turns up -- would make the payment rate a property of
  * the configured weights rather than of `session.payment-probability`, and §2.3's step-up volume
  * is derived from the latter.
  */
final class BusinessActions private (
    val opening: BusinessActionConfig,
    ordinary: Vector[BusinessActionConfig],
    ordinaryCumulative: Array[Double],
    stepUp: Vector[BusinessActionConfig],
    stepUpCumulative: Array[Double],
):

  /** One of the actions that carry no ACR requirement, by configured weight. */
  def pickOrdinary(random: RandomSource): BusinessActionConfig =
    weighted(ordinary, ordinaryCumulative, random)

  /** One of the actions that do, which is what makes the session's payment provoke a step-up.
    * `None` when the campaign configured none -- a calibration run against `mockapi` has no
    * step-up path at all, and inventing one would measure a flow the plan did not ask for.
    */
  def pickStepUp(random: RandomSource): Option[BusinessActionConfig] =
    if stepUp.isEmpty then None else Some(weighted(stepUp, stepUpCumulative, random))

  /** The call as it goes out over §8.6.
    *
    * Path parameters are substituted from the virtual user's own id rather than from a fixture
    * list: the ten paths of design doc §3 carry `{accountId}`/`{cardId}`/`{deviceId}`, `mockapi`
    * does not look at them, and edge matches the *template* -- so what matters is only that the
    * emitted path is a plausible single segment and that it is stable per user, which keeps a
    * campaign's URL cardinality proportional to the population rather than to the request count.
    *
    * Write actions deliberately carry a body with no `amount` field. The two payment endpoints'
    * CEL rule (`versola.loadgen.provision.CampaignBlueprint.amountRule`) is guarded by
    * `!has(request.body.amount)`, so an amountless body is allowed -- which is what keeps the
    * campaign's 403 rate coming from `retail-basic` alone, as design doc §3 intends, instead of
    * from payments the emulator priced over the threshold itself.
    */
  def call(action: BusinessActionConfig, userId: Long): Either[String, ActionCall] =
    BusinessActions.methodOf(action).map: method =>
      ActionCall(method, BusinessActions.resolvePath(action.path, userId), BusinessActions.bodyFor(method))

  private def weighted(
      actions: Vector[BusinessActionConfig],
      cumulative: Array[Double],
      random: RandomSource,
  ): BusinessActionConfig =
    val draw = random.nextDouble() * cumulative(cumulative.length - 1)
    var index = 0
    while index < cumulative.length - 1 && draw >= cumulative(index) do index += 1
    actions(index)

object BusinessActions:

  /** Rejects the `actions` lists that produce a campaign running something other than what it
    * says, rather than one that fails.
    *
    * The opening call is the list's first entry, not an entry flagged as such: design doc §3
    * lists `GET /accounts` first and calls it "the first call of every session", and §5's HOCON
    * carries no marker to add one from. It is checked for carrying no ACR, because an opening
    * call that demanded a step-up would put one at the head of every single session and treble
    * the step-up rate §2.3 sizes the SUT against.
    */
  def from(configured: List[BusinessActionConfig]): Either[String, BusinessActions] =
    for
      opening <- configured.headOption.toRight("actions must not be empty")
      _ <- Either.cond(opening.acr.isEmpty, (), s"the opening action '${opening.name}' must not require an acr")
      _ <- configured.find(action => !(action.weight > 0.0)) match
        case Some(action) => Left(s"action '${action.name}' must have a positive weight, got ${action.weight}")
        case None => Right(())
      _ <- configured.find(action => methodOf(action).isLeft) match
        case Some(action) => Left(s"action '${action.name}' has an unknown method '${action.method}'")
        case None => Right(())
      ordinary = configured.filter(_.acr.isEmpty).toVector
      _ <- Either.cond(ordinary.nonEmpty, (), "actions must contain at least one action with no acr requirement")
    yield
      val stepUp = configured.filter(_.acr.isDefined).toVector
      BusinessActions(opening, ordinary, cumulative(ordinary), stepUp, cumulative(stepUp))

  /** `Method.fromString` answers `Method.CUSTOM` for anything it does not know, so a typo in the
    * config would otherwise reach the SUT as a request nobody routes and be counted as an
    * unexpected status against the error budget.
    */
  private[scenario] def methodOf(action: BusinessActionConfig): Either[String, Method] =
    Method.fromString(action.method) match
      case Method.CUSTOM(_) => Left(action.method)
      case known => Right(known)

  /** A single path segment, stable per user and per template position. Hex rather than the raw
    * id so the emitted path does not read as the emulator's own bookkeeping key leaking into the
    * SUT's URLs, and so two templates on one path get different values.
    */
  private[scenario] def resolvePath(template: String, userId: Long): String =
    if !template.contains('{') then template
    else
      val out = StringBuilder(template.length)
      var index = 0
      var parameter = 0
      while index < template.length do
        val open = template.indexOf('{', index)
        val close = if open < 0 then -1 else template.indexOf('}', open)
        if open < 0 || close < 0 then
          out.append(template.substring(index))
          index = template.length
        else
          out.append(template.substring(index, open))
          out.append(java.lang.Long.toHexString(userId * 31L + parameter))
          parameter += 1
          index = close + 1
      out.result()

  private[scenario] def bodyFor(method: Method): Option[String] =
    if method == Method.POST || method == Method.PUT then Some(writeBody) else None

  private val writeBody = """{"reference":"loadgen"}"""

  private def cumulative(actions: Vector[BusinessActionConfig]): Array[Double] =
    val running = Array.ofDim[Double](actions.length)
    var total = 0.0
    var index = 0
    while index < actions.length do
      total += actions(index).weight
      running(index) = total
      index += 1
    running
