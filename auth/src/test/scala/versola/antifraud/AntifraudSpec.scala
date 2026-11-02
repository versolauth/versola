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

  private val on = AntifraudTestSettings.decision.copy(enabled = true)
  private val thresholds = AntifraudTestSettings.tuning.copy(challengeAt = 5, blockAt = 10)

  /** An engine whose failure asks for friction; `engine` has the default, `allow`. */
  private def failingEngine(ruleSets: List[Antifraud.RuleSet]) =
    AntifraudTestSettings.engine(on.copy(failureAction = DecisionAction.Challenge), ruleSets, thresholds)

  private def engine(ruleSets: List[Antifraud.RuleSet]) = AntifraudTestSettings.engine(on, ruleSets, thresholds)

  def spec = suite("Antifraud")(
    test("is a no-op and runs no rule while disabled") {
      val boom = rule("boom", 100).copy(test = _ => throw RuntimeException("ran"))
      AntifraudTestSettings.engine(AntifraudTestSettings.decision, List(set(boom)), AntifraudTestSettings.tuning.copy(blockAt = 1)).evaluate(ctx).map(d => assertTrue(d == Decision.allow))
    },
    test("maps the score to the harshest threshold reached and lists the fired rules") {
      val rs = List(set(rule("a", 3), rule("b", 4), rule("c", 9, hit = false)))
      for
        d <- engine(rs).evaluate(ctx)
        low <- engine(List(set(rule("a", 1)))).evaluate(ctx)
        none <- engine(List(set(rule("z", 1, hit = false)))).evaluate(ctx)
        blocked <- engine(List(set(rule("a", 6), rule("b", 4)))).evaluate(ctx)
      yield assertTrue(
        d == Decision(DecisionAction.Challenge, 7, List("a", "b")),
        low == Decision(DecisionAction.Allow, 1, List("a")),
        none == Decision.allow,
        blocked.action == DecisionAction.Block,
      )
    },
    test("a forcing rule wins over a milder score, never over a harsher one") {
      for
        deny <- engine(List(set(rule("deny", 0, Some(DecisionAction.Block))))).evaluate(ctx)
        fake <- engine(List(set(rule("fake", 0, Some(DecisionAction.Fake)), rule("w", 10)))).evaluate(ctx)
      yield assertTrue(deny.action == DecisionAction.Block, fake.action == DecisionAction.Block)
    },
    test("fails as failure-action says when a rule throws or a rule set fails") {
      val boom = rule("boom", 1).copy(test = _ => throw RuntimeException("x"))
      val failing = new Antifraud.RuleSet { def rules = ZIO.die(RuntimeException("db")) }
      for
        a <- failingEngine(List(set(boom))).evaluate(ctx)
        b <- failingEngine(List(failing)).evaluate(ctx)
        open <- engine(List(set(boom))).evaluate(ctx)
      yield assertTrue(
        a == Decision(DecisionAction.Challenge, 0, List("antifraud_error")),
        b == a,
        open == Decision(DecisionAction.Allow, 0, List("antifraud_error")),
      )
    },
    test("fails as failure-action says on timeout") {
      val slow = new Antifraud.RuleSet { def rules = ZIO.never }
      for
        f <- failingEngine(List(slow)).evaluate(ctx).fork
        _ <- TestClock.adjust(1.second)
        d <- f.join
      yield assertTrue(d.firedRules == List("antifraud_error"), d.action == DecisionAction.Challenge)
    },
  )
