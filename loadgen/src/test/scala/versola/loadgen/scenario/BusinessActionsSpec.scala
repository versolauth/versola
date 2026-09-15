package versola.loadgen.scenario

import versola.loadgen.config.BusinessActionConfig
import versola.loadgen.protocol.ActionCall
import versola.loadgen.scheduler.RandomSource
import zio.http.Method
import zio.test.*

/** The three draws of design doc §3's action list, and the two ways an `actions` block can make a
  * campaign run something other than what it says.
  */
object BusinessActionsSpec extends ZIOSpecDefault:

  private val accounts = BusinessActionConfig("accounts", 30.0, "GET", "/resources/core/accounts", None)
  private val balance = BusinessActionConfig("balance", 20.0, "GET", "/resources/core/accounts/{accountId}/balance", None)
  private val cardBlock = BusinessActionConfig("card-block", 5.0, "POST", "/resources/core/cards/{cardId}/block", None)
  private val payment = BusinessActionConfig("payment", 10.0, "POST", "/resources/pay/transfers", Some("otp-level"))

  private val configured = List(accounts, balance, cardBlock, payment)

  private def built: BusinessActions =
    BusinessActions.from(configured).getOrElse(throw AssertionError("the fixture must be a valid action list"))

  def spec = suite("BusinessActions")(
    test("the opening call is the list's first entry, which design doc §3 makes GET /accounts") {
      assertTrue(built.opening == accounts)
    },
    // An opening call that demanded an ACR would put a step-up at the head of every session and
    // treble the rate §2.3 sizes the SUT against, so it is rejected rather than run.
    test("an opening call that requires an acr is rejected") {
      assertTrue(BusinessActions.from(payment :: configured).isLeft)
    },
    test("an empty list, a non-positive weight and an unknown method are each rejected") {
      assertTrue(
        BusinessActions.from(Nil).isLeft,
        BusinessActions.from(List(accounts, balance.copy(weight = 0.0))).isLeft,
        BusinessActions.from(List(accounts, balance.copy(method = "FETCH"))).isLeft,
      )
    },
    test("a list of nothing but acr-gated actions is rejected, since no ordinary slot could be filled") {
      assertTrue(BusinessActions.from(List(payment)).isLeft)
    },
    test("the ordinary draw never returns an acr-gated action and follows the configured weights") {
      val random = RandomSource.seeded(4242L)
      val drawn = Vector.fill(60000)(built.pickOrdinary(random))
      val share = drawn.count(_ == accounts).toDouble / drawn.length
      assertTrue(
        drawn.forall(_.acr.isEmpty),
        // 30 of the 55 ordinary weight.
        math.abs(share - 30.0 / 55.0) < 0.02,
        drawn.toSet == Set(accounts, balance, cardBlock),
      )
    },
    test("the step-up draw returns only acr-gated actions, and nothing when none is configured") {
      val random = RandomSource.seeded(7L)
      assertTrue(
        Vector.fill(100)(built.pickStepUp(random)).forall(_.contains(payment)),
        BusinessActions.from(List(accounts, balance)).map(_.pickStepUp(random)).contains(None),
      )
    },
    test("path parameters resolve to one stable segment per user, and two templates differ") {
      val call = built.call(balance, 4242L)
      val again = built.call(balance, 4242L)
      val other = built.call(balance, 4243L)
      val two = BusinessActions.resolvePath("/resources/core/accounts/{accountId}/cards/{cardId}", 4242L)
      assertTrue(
        call == again,
        call != other,
        call.exists(!_.path.contains('{')),
        // Stable per user is what keeps URL cardinality proportional to the population rather
        // than to the request count.
        call.exists(_.path.split('/').length == balance.path.split('/').length),
        two.split('/').toSet.size == two.split('/').length,
      )
    },
    test("a path with no template is emitted unchanged") {
      assertTrue(built.call(accounts, 99L) == Right(ActionCall(Method.GET, accounts.path, None)))
    },
    // The body carries no `amount`, because the payment endpoints' CEL rule is guarded by
    // `!has(request.body.amount)` -- a priced body would add 403s the campaign did not plan.
    test("writes carry an amountless body and reads carry none") {
      assertTrue(
        built.call(cardBlock, 1L).exists(_.body.contains("""{"reference":"loadgen"}""")),
        built.call(cardBlock, 1L).exists(!_.body.exists(_.contains("amount"))),
        built.call(accounts, 1L).exists(_.body.isEmpty),
      )
    },
  )
