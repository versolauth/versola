package versola.antifraud

import versola.oauth.client.model.TenantId
import versola.util.UnitSpecBase
import zio.ZIO
import zio.test.*

import java.time.Instant

object SmsBudgetSpec extends UnitSpecBase:

  private val t0 = Instant.parse("2026-10-21T10:00:00Z")
  private val tenant = Some(TenantId("tenant-a"))

  import SmsBudget.Decision

  /** Charges `count` codes, as the sign-in does for real sends; those the budget refuses are not counted. */
  private def sendTo(budget: SmsBudget, prefix: String, count: Int, at: Instant = t0, tenantId: Option[TenantId] = tenant): Unit =
    (1 to count).foreach(_ => budget.decide(tenantId, prefix, at, charge = true))

  private def allowed(budget: SmsBudget, tenantId: Option[TenantId], prefix: String, at: Instant): Boolean =
    budget.decide(tenantId, prefix, at, charge = false) == Decision.Allowed

  private def budgetOf(
      sentPerHour: Int = 0,
      minVolume: Int = 0,
      minConversion: Double = 0.1,
      maxRanges: Int = AntifraudTestSettings.smsSizing.maxRanges,
  ) =
    SmsBudget(
      AntifraudTestSettings.smsBudget,
      AntifraudTestSettings.smsSizing.copy(maxRanges = maxRanges),
      SmsLimits(SmsLimit(sentPerHour, minVolume, minConversion), Map.empty),
    )

  def spec = suite("SmsBudget")(
    test("without enforcing, a closed range is answered WouldRefuse with its reason, and the code that goes out is counted all the same") {
      val budget = budgetOf(sentPerHour = 3)
      def shadow() = budget.assess(tenant, "491512", t0, charge = true, conversionBreaker = true, enforce = false)
      val answers = (1 to 5).map(_ => shadow())
      val afterwards = budget.assess(tenant, "491512", t0, charge = false, conversionBreaker = true, enforce = true)
      assertTrue(
        answers.map(_.decision) == List(Decision.Allowed, Decision.Allowed, Decision.Allowed, Decision.WouldRefuse, Decision.WouldRefuse),
        answers.drop(3).forall(_.reason.contains(SmsBudget.Reason.Cap)),
        // The window holds what went out (five), so enforcing from now on starts from the truth.
        afterwards.decision == Decision.Refused,
      )
    },
    test("says why: the cap, or too few verified of those sent") {
      val capped = budgetOf(sentPerHour = 2)
      sendTo(capped, "491512", 2)
      val cold = budgetOf(minVolume = 5, minConversion = 0.5)
      sendTo(cold, "491512", 5)
      assertTrue(
        capped.assess(tenant, "491512", t0, charge = false, conversionBreaker = true, enforce = true).reason.contains(SmsBudget.Reason.Cap),
        cold.assess(tenant, "491512", t0, charge = false, conversionBreaker = true, enforce = true).reason.contains(SmsBudget.Reason.Conversion),
        cold.assess(tenant, "491512", t0, charge = false, conversionBreaker = false, enforce = true).reason.isEmpty,
        SmsBudget.Reason.Cap.label == "cap",
        SmsBudget.Reason.Conversion.label == "conversion",
      )
    },
    test("allows everything while no limit is configured, and tracks nothing") {
      val budget = budgetOf()
      sendTo(budget, "491512", 10000)
      assertTrue(allowed(budget, tenant, "491512", t0), budget.decide(tenant, "491512", t0, charge = true) == Decision.Allowed, budget.size == 0)
    },
    test("closes a range after sentPerHour codes, and only that range of that tenant") {
      val budget = budgetOf(sentPerHour = 5)
      sendTo(budget, "491512", 5)
      assertTrue(
        !allowed(budget, tenant, "491512", t0),
        // The same range, other digits after the prefix: same range.
        !allowed(budget, tenant, "491519", t0),
        allowed(budget, tenant, "491612", t0),
        allowed(budget, Some(TenantId("tenant-b")), "491512", t0),
        allowed(budget, None, "491512", t0),
      )
    },
    test("opens again as the hour moves on") {
      val budget = budgetOf(sentPerHour = 5)
      sendTo(budget, "491512", 5, at = t0)
      assertTrue(
        !allowed(budget, tenant, "491512", t0.plusSeconds(59 * 60)),
        allowed(budget, tenant, "491512", t0.plusSeconds(61 * 60)),
      )
    },
    test("closes a range that is not converting once it has enough volume, and not before") {
      val budget = budgetOf(minVolume = 20, minConversion = 0.2)
      sendTo(budget, "491512", 19)
      val early = allowed(budget, tenant, "491512", t0)
      sendTo(budget, "491512", 1)
      val closed = !allowed(budget, tenant, "491512", t0)
      (1 to 4).foreach(_ => budget.verified(tenant, "491512", t0))
      val converting = allowed(budget, tenant, "491512", t0)
      assertTrue(early, closed, converting)
    },
    test("a range that converts well is never closed by volume alone") {
      val budget = budgetOf(minVolume = 20, minConversion = 0.2)
      (1 to 1000).foreach: i =>
        budget.decide(tenant, "4915", t0, charge = true)
        if i % 10 != 0 then budget.verified(tenant, "4915", t0)
      assertTrue(allowed(budget, tenant, "4915", t0))
    },
    test("a full table makes room for a new range, so the limit cannot be filled up, and the busy ranges stay closed") {
      val budget = budgetOf(sentPerHour = 100, maxRanges = 200)
      val busy = Some(TenantId("busy"))
      List("4915", "4916", "4917").foreach(prefix => sendTo(budget, prefix, 150, tenantId = busy))
      // A flood of made-up ranges (one code each) from thousands of tenants and prefixes.
      val results = (1 to 20000).map(i => budget.decide(Some(TenantId(s"t$i")), "4915", t0.plusSeconds(1), charge = true))
      assertTrue(
        budget.size == 200,
        // Every flooding range is tracked as it comes, none goes through untracked.
        results.forall(_ == Decision.Allowed),
        budget.drainEvicted() > 19000,
        // Three of 200 slots are busy: all eight of a sample being busy is 0.015 to the eighth power a try.
        List("4915", "4916", "4917").forall(prefix => !allowed(budget, busy, prefix, t0.plusSeconds(1))),
      )
    },
    test("the least codes in the last ten minutes decide: an older busy range stays, an idle one goes") {
      val budget = budgetOf(sentPerHour = 100, maxRanges = 2)
      sendTo(budget, "4915", 5, at = t0)
      sendTo(budget, "4916", 1, at = t0.minusSeconds(40 * 60))
      // The whole table is the sample.
      budget.decide(tenant, "4917", t0, charge = true)
      assertTrue(budget.size == 2, budget.drainEvicted() == 1, budget.decide(tenant, "4915", t0, charge = true) == Decision.Allowed)
    },
    test("forgetting a prefix empties the ranges it is in or contains of every tenant, and forgetting none empties them all") {
      val budget = budgetOf(sentPerHour = 1)
      sendTo(budget, "4915", 1)
      sendTo(budget, "4915", 1, tenantId = Some(TenantId("tenant-b")))
      sendTo(budget, "4916", 1)
      budget.forget(Some("4915"))
      val afterOne =
        (allowed(budget, tenant, "4915", t0), allowed(budget, Some(TenantId("tenant-b")), "4915", t0), allowed(budget, tenant, "4916", t0))
      sendTo(budget, "4915", 1)
      // A longer value (a whole number's digits) names its range.
      budget.forget(Some("491512"))
      val afterLong = allowed(budget, tenant, "4915", t0)
      budget.forget(None)
      assertTrue(afterOne == (true, true, false), afterLong, allowed(budget, tenant, "4916", t0), budget.size == 0)
    },
    test("forgets a range that has been quiet for the hour") {
      val budget = budgetOf(sentPerHour = 1)
      sendTo(budget, "4915", 1)
      budget.evict(t0.plusSeconds(30 * 60))
      val kept = budget.size
      budget.evict(t0.plusSeconds(2 * 3600))
      assertTrue(kept == 1, budget.size == 0)
    },
    test("a check that charges nothing neither counts nor creates a range") {
      val budget = budgetOf(sentPerHour = 2)
      (1 to 10).foreach(_ => budget.decide(tenant, "4915", t0, charge = false))
      assertTrue(budget.size == 0, allowed(budget, tenant, "4915", t0))
    },
    test("a code given back is not counted") {
      val budget = budgetOf(sentPerHour = 2)
      sendTo(budget, "4915", 2)
      val closed = !allowed(budget, tenant, "4915", t0)
      budget.release(tenant, "4915", t0)
      assertTrue(closed, allowed(budget, tenant, "4915", t0))
    },
    test("requests arriving at once cannot go past the limit") {
      val budget = budgetOf(sentPerHour = 10)
      for results <- ZIO.foreachPar(1 to 200)(_ => ZIO.attemptBlocking(budget.decide(tenant, "4915", t0, charge = true)).orDie)
      yield assertTrue(results.count(_ == Decision.Allowed) == 10)
    },
    test("a code given back after its minute is over is taken from its own minute, and a reused minute is left alone") {
      val budget = budgetOf(sentPerHour = 2)
      sendTo(budget, "4915", 2, at = t0)
      budget.release(tenant, "4915", t0)
      val reopened = allowed(budget, tenant, "4915", t0.plusSeconds(90))
      // the same slot, an hour later, holds other minutes' data: a late give-back of the old charge must not touch it
      sendTo(budget, "4915", 1, at = t0.plusSeconds(3600))
      budget.release(tenant, "4915", t0)
      val untouched = budget.decide(tenant, "4915", t0.plusSeconds(3600), charge = true) == Decision.Allowed
      val closed = !allowed(budget, tenant, "4915", t0.plusSeconds(3600))
      assertTrue(reopened, untouched, closed)
    },
    test("the conversion breaker can be left out for a request, the hourly limit cannot") {
      val budget = budgetOf(sentPerHour = 100, minVolume = 3, minConversion = 0.5)
      sendTo(budget, "4915", 3)
      assertTrue(
        budget.decide(tenant, "4915", t0, charge = false, conversionBreaker = true) == Decision.Refused,
        budget.decide(tenant, "4915", t0, charge = false, conversionBreaker = false) == Decision.Allowed,
      )
    },
  )
