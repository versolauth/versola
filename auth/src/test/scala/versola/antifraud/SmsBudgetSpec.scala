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
    SmsBudget(AntifraudTestSettings.smsBudget, AntifraudTestSettings.smsSizing.copy(maxRanges = maxRanges), SmsLimits(SmsLimit(sentPerHour, minVolume, minConversion), Map.empty))

  def spec = suite("SmsBudget")(
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
    test("stops tracking new ranges at the cap and lets them through, so the limit cannot be filled up") {
      val budget = budgetOf(sentPerHour = 1, maxRanges = 2)
      sendTo(budget, "4915", 1)
      sendTo(budget, "4916", 1)
      val untracked = budget.decide(tenant, "4917", t0, charge = true)
      assertTrue(budget.size == 2, !allowed(budget, tenant, "4915", t0), allowed(budget, tenant, "4917", t0), untracked == Decision.Untracked)
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
