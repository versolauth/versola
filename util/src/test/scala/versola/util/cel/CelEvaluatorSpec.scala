package versola.util.cel

import versola.util.cel.CelEvaluator.EvaluationError
import zio.*
import zio.test.*
import zio.test.Assertion.*

import scala.jdk.CollectionConverters.MapHasAsJava

object CelEvaluatorSpec extends ZIOSpecDefault:

  private def make: UIO[CelEvaluator] =
    Ref.make(Map.empty[String, Either[CelEvaluator.CompileError, CelEvaluator.Program]])
      .map(CelEvaluator.Impl(_))

  private val tokenContext: Map[String, AnyRef] = Map(
    "token"   -> Map[String, AnyRef]("role" -> "admin", "scope" -> "read write").asJava,
    "user"    -> Map.empty[String, AnyRef].asJava,
    "request" -> Map[String, AnyRef]("method" -> "GET", "path" -> "/users").asJava,
  )

  def spec = suite("CelEvaluator")(
    suite("validate")(
      test("returns Program for a valid expression") {
        for
          evaluator <- make
          program   <- evaluator.validate("token.role == 'admin'").either
        yield assertTrue(program.isRight)
      },
      test("fails with CompileError for invalid syntax") {
        for
          evaluator <- make
          result    <- evaluator.validate("token.role ==").either
        yield assertTrue(
          result.isLeft,
          result.swap.exists(_.expression == "token.role =="),
          result.swap.exists(_.message.nonEmpty),
        )
      },
      test("fails with CompileError for unknown variable") {
        for
          evaluator <- make
          result    <- evaluator.validate("unknown.field").either
        yield assertTrue(
          result.isLeft,
          result.swap.exists(_.expression == "unknown.field"),
        )
      },
      test("returns the same CompileError on repeated validation of bad expression") {
        for
          evaluator <- make
          first     <- evaluator.validate("(unterminated").either
          second    <- evaluator.validate("(unterminated").either
        yield assertTrue(
          first.isLeft,
          second.isLeft,
          first.swap.toOption == second.swap.toOption,
        )
      },
      test("fails a division by zero folded out of a constant subexpression") {
        for
          evaluator <- make
          result    <- evaluator.validate("1 / 0 == 0").either
        yield assertTrue(result.isLeft)
      },
      test("fails a malformed regex literal passed to matches") {
        for
          evaluator <- make
          result    <- evaluator.validate("token.role.matches('[')").either
        yield assertTrue(result.isLeft)
      },
      test("fails a malformed timestamp literal") {
        for
          evaluator <- make
          result    <- evaluator.validate("timestamp('not-a-time')").either
        yield assertTrue(result.isLeft)
      },
      test("fails a malformed duration literal") {
        for
          evaluator <- make
          result    <- evaluator.validate("duration('bad')").either
        yield assertTrue(result.isLeft)
      },
      test("does not fail a value-dependent division, since the divisor isn't a constant") {
        for
          evaluator <- make
          result    <- evaluator.validate("100 / token.divisor == 1").either
        yield assertTrue(result.isRight)
      },
      test("does not fail an arithmetic expression on DYN-typed claims, since type-checking is off for them") {
        for
          evaluator <- make
          result    <- evaluator.validate("token.role + 1 > 2").either
        yield assertTrue(result.isRight)
      },
      test("does not fail a mixed-type list literal, since HomogeneousLiteralValidator is deliberately not wired in") {
        for
          evaluator <- make
          result    <- evaluator.validate("token.role in ['a', 2, 'c']").either
        yield assertTrue(result.isRight)
      },
    ),
    suite("compile (safe)")(
      test("returns a working Program for a valid expression") {
        for
          evaluator <- make
          program   <- evaluator.compile("token.role == 'admin'")
          result    <- program.evaluateBoolean(tokenContext)
        yield assertTrue(result)
      },
      test("returns a Program that fails as Broken for an expression that doesn't compile") {
        for
          evaluator <- make
          program   <- evaluator.compile("(unterminated")
          boolean   <- program.evaluateBoolean(tokenContext).either
          string    <- program.evaluateString(tokenContext).either
        yield assertTrue(
          boolean == Left(EvaluationError.Broken),
          string == Left(EvaluationError.Broken),
        )
      },
    ),
    suite("Program evaluation")(
      test("evaluateBoolean returns true when expression matches context") {
        for
          evaluator <- make
          program   <- evaluator.compile("token.role == 'admin' && request.method == 'GET'")
          result    <- program.evaluateBoolean(tokenContext)
        yield assertTrue(result)
      },
      test("evaluateBoolean fails as Broken when the expression returns a non-boolean") {
        for
          evaluator <- make
          program   <- evaluator.compile("token.role")
          result    <- program.evaluateBoolean(tokenContext).either
        yield assertTrue(result == Left(EvaluationError.Broken))
      },
      test("evaluateBoolean fails as DataMissing when the expression reads a claim the token lacks") {
        for
          evaluator <- make
          program   <- evaluator.compile("token.missing.deep.path == 'x'")
          result    <- program.evaluateBoolean(tokenContext).either
        yield assertTrue(result == Left(EvaluationError.DataMissing))
      },
      test("evaluateBoolean fails as Broken for an evaluation error that isn't absent data") {
        for
          evaluator <- make
          program   <- evaluator.compile("1 / 0 == 0")
          result    <- program.evaluateBoolean(tokenContext).either
        yield assertTrue(result == Left(EvaluationError.Broken))
      },
      test("an absent claim can be guarded with has(), leaving the rule evaluable") {
        for
          evaluator <- make
          program   <- evaluator.compile("has(user.plan) && user.plan == 'premium'")
          result    <- program.evaluateBoolean(tokenContext)
        yield assertTrue(!result)
      },
      test("evaluateString returns Some for string-typed expression") {
        for
          evaluator <- make
          program   <- evaluator.compile("token.role")
          result    <- program.evaluateString(tokenContext)
        yield assertTrue(result.contains("admin"))
      },
      test("evaluateString fails as DataMissing when the expression reads a claim the token lacks") {
        for
          evaluator <- make
          program   <- evaluator.compile("token.missing.deep.path")
          result    <- program.evaluateString(tokenContext).either
        yield assertTrue(result == Left(EvaluationError.DataMissing))
      },
      test("an absent claim can be guarded with has(), leaving an inject rule evaluable") {
        for
          evaluator <- make
          program   <- evaluator.compile("has(user.plan) ? user.plan : ''")
          result    <- program.evaluateString(tokenContext)
        yield assertTrue(result.contains(""))
      },
    ),
    suite("cache")(
      test("compile and validate share results across calls") {
        for
          cacheRef  <- Ref.make(Map.empty[String, Either[CelEvaluator.CompileError, CelEvaluator.Program]])
          evaluator  = CelEvaluator.Impl(cacheRef)
          _         <- evaluator.validate("token.role == 'admin'").either
          _         <- evaluator.compile("token.role == 'admin'")
          cached    <- cacheRef.get
        yield assertTrue(cached.size == 1, cached.contains("token.role == 'admin'"))
      },
      test("cache stores failed compilations so they are not retried") {
        for
          cacheRef  <- Ref.make(Map.empty[String, Either[CelEvaluator.CompileError, CelEvaluator.Program]])
          evaluator  = CelEvaluator.Impl(cacheRef)
          _         <- evaluator.validate("(broken").either
          _         <- evaluator.validate("(broken").either
          cached    <- cacheRef.get
        yield assertTrue(cached.size == 1, cached.get("(broken").exists(_.isLeft))
      },
    ),
  )
