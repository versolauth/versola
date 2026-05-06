package versola.util.cel

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
    ),
    suite("compile (safe)")(
      test("returns a working Program for a valid expression") {
        for
          evaluator <- make
          program   <- evaluator.compile("token.role == 'admin'")
          result    <- program.evaluateBoolean(tokenContext)
        yield assertTrue(result)
      },
      test("returns FailSafe Program (false) for invalid expression") {
        for
          evaluator <- make
          program   <- evaluator.compile("(unterminated")
          boolean   <- program.evaluateBoolean(tokenContext)
          string    <- program.evaluateString(tokenContext)
        yield assertTrue(!boolean, string.isEmpty)
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
      test("evaluateBoolean returns false on type mismatch instead of failing") {
        for
          evaluator <- make
          program   <- evaluator.compile("token.role")
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
      test("evaluateString returns None when evaluation throws") {
        for
          evaluator <- make
          program   <- evaluator.compile("token.missing.deep.path")
          result    <- program.evaluateString(tokenContext)
        yield assertTrue(result.isEmpty)
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
