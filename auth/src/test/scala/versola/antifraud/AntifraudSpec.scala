package versola.antifraud

import versola.oauth.client.model.ClientId
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

object AntifraudSpec extends UnitSpecBase:

  private val ctx = AntifraudContext(None, ClientId("c"), AntifraudAction.otp_request, None, None, None, Some("1.2.3.4"), None, None, None, Map.empty)

  private def rule(name: String, weight: Int, forces: Option[DecisionAction] = None, hit: Boolean = true) =
    AntifraudRule(name, weight, forces, _ => hit)

  private def set(rs: AntifraudRule*): Antifraud.RuleSet = new Antifraud.RuleSet:
    def rules = ZIO.succeed(rs.toList)

  private val on = Antifraud.Settings(enabled = true, delayAt = 1, challengeAt = 5, reviewAt = 8, blockAt = 10)

  def spec = suite("Antifraud")(
    test("is a no-op and runs no rule while disabled") {
      val boom = rule("boom", 100).copy(test = _ => throw RuntimeException("ran"))
      Antifraud.Impl(Antifraud.Settings(blockAt = 1), List(set(boom))).evaluate(ctx).map(d => assertTrue(d == Decision.allow))
    },
    test("maps the score to the harshest threshold reached and lists the fired rules") {
      val rs = List(set(rule("a", 3), rule("b", 4), rule("c", 9, hit = false)))
      for
        d <- Antifraud.Impl(on, rs).evaluate(ctx)
        low <- Antifraud.Impl(on, List(set(rule("a", 1)))).evaluate(ctx)
        none <- Antifraud.Impl(on, List(set(rule("z", 1, hit = false)))).evaluate(ctx)
        blocked <- Antifraud.Impl(on, List(set(rule("a", 6), rule("b", 4)))).evaluate(ctx)
      yield assertTrue(
        d == Decision(DecisionAction.Challenge, 7, List("a", "b")),
        low.action == DecisionAction.Delay,
        none == Decision.allow,
        blocked.action == DecisionAction.Block,
      )
    },
    test("a forcing rule wins over a milder score, never over a harsher one") {
      for
        deny <- Antifraud.Impl(on, List(set(rule("deny", 0, Some(DecisionAction.Block))))).evaluate(ctx)
        fake <- Antifraud.Impl(on, List(set(rule("fake", 0, Some(DecisionAction.Fake)), rule("w", 10)))).evaluate(ctx)
      yield assertTrue(deny.action == DecisionAction.Block, fake.action == DecisionAction.Block)
    },
    test("fails open with friction when a rule throws or a rule set fails") {
      val boom = rule("boom", 1).copy(test = _ => throw RuntimeException("x"))
      val failing = new Antifraud.RuleSet { def rules = ZIO.die(RuntimeException("db")) }
      for
        a <- Antifraud.Impl(on, List(set(boom))).evaluate(ctx)
        b <- Antifraud.Impl(on, List(failing)).evaluate(ctx)
      yield assertTrue(a == Decision(DecisionAction.Challenge, 0, List("antifraud_error")), b == a)
    },
    test("fails open with friction on timeout") {
      val slow = new Antifraud.RuleSet { def rules = ZIO.never }
      for
        f <- Antifraud.Impl(on, List(slow)).evaluate(ctx).fork
        _ <- TestClock.adjust(1.second)
        d <- f.join
      yield assertTrue(d.firedRules == List("antifraud_error"), d.action == DecisionAction.Challenge)
    },
  )
