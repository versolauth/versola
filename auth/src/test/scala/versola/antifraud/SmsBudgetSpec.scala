package versola.antifraud

import versola.oauth.client.model.TenantId
import versola.util.UnitSpecBase
import zio.test.*

import java.time.Instant

object SmsBudgetSpec extends UnitSpecBase:

  private val t0 = Instant.parse("2026-10-21T10:00:00Z")
  private val tenant = Some(TenantId("tenant-a"))

  private def sendTo(budget: SmsBudget, prefix: String, count: Int, at: Instant = t0, tenantId: Option[TenantId] = tenant): Unit =
    (1 to count).foreach(_ => budget.sent(tenantId, prefix, at))

  def spec = suite("SmsBudget")(
    test("allows everything while no limit is configured, and tracks nothing") {
      val budget = SmsBudget(SmsBudget.Settings())
      sendTo(budget, "491512", 10000)
      assertTrue(budget.allows(tenant, "491512", t0), budget.size == 0)
    },
    test("closes a range after sentPerHour codes, and only that range of that tenant") {
      val budget = SmsBudget(SmsBudget.Settings(sentPerHour = 5))
      sendTo(budget, "491512", 5)
      assertTrue(
        !budget.allows(tenant, "491512", t0),
        // The same range, other digits after the prefix: same range.
        !budget.allows(tenant, "491519", t0),
        budget.allows(tenant, "491612", t0),
        budget.allows(Some(TenantId("tenant-b")), "491512", t0),
        budget.allows(None, "491512", t0),
      )
    },
    test("opens again as the hour moves on") {
      val budget = SmsBudget(SmsBudget.Settings(sentPerHour = 5))
      sendTo(budget, "491512", 5, at = t0)
      assertTrue(
        !budget.allows(tenant, "491512", t0.plusSeconds(59 * 60)),
        budget.allows(tenant, "491512", t0.plusSeconds(61 * 60)),
      )
    },
    test("closes a range that is not converting once it has enough volume, and not before") {
      val budget = SmsBudget(SmsBudget.Settings(minVolume = 20, minConversion = 0.2))
      sendTo(budget, "491512", 19)
      val early = budget.allows(tenant, "491512", t0)
      sendTo(budget, "491512", 1)
      val closed = !budget.allows(tenant, "491512", t0)
      (1 to 4).foreach(_ => budget.verified(tenant, "491512", t0))
      val converting = budget.allows(tenant, "491512", t0)
      assertTrue(early, closed, converting)
    },
    test("a range that converts well is never closed by volume alone") {
      val budget = SmsBudget(SmsBudget.Settings(minVolume = 20, minConversion = 0.2))
      sendTo(budget, "4915", 1000)
      (1 to 900).foreach(_ => budget.verified(tenant, "4915", t0))
      assertTrue(budget.allows(tenant, "4915", t0))
    },
    test("stops tracking new ranges at the cap and lets them through, so the limit cannot be filled up") {
      val budget = SmsBudget(SmsBudget.Settings(sentPerHour = 1, maxRanges = 2))
      sendTo(budget, "4915", 1)
      sendTo(budget, "4916", 1)
      sendTo(budget, "4917", 5)
      assertTrue(budget.size == 2, !budget.allows(tenant, "4915", t0), budget.allows(tenant, "4917", t0))
    },
    test("forgets a range that has been quiet for the hour") {
      val budget = SmsBudget(SmsBudget.Settings(sentPerHour = 1))
      sendTo(budget, "4915", 1)
      budget.evict(t0.plusSeconds(30 * 60))
      val kept = budget.size
      budget.evict(t0.plusSeconds(2 * 3600))
      assertTrue(kept == 1, budget.size == 0)
    },
  )
