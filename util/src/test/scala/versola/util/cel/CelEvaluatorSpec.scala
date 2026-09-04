package versola.util.cel

import versola.util.http.Observability
import zio.*
import zio.logging.{LogContext, logContext}
import zio.test.*
import zio.test.Assertion.*

import scala.jdk.CollectionConverters.MapHasAsJava

object CelEvaluatorSpec extends ZIOSpecDefault:

  private def make: UIO[CelEvaluator] =
    Ref.make(Map.empty[String, Either[CelEvaluator.CompileError, CelEvaluator.Program]])
      .map(CelEvaluator.Impl(_))

  private def celError: UIO[Option[Observability.ErrorDetails]] =
    logContext.get.map(_.get(Observability.error))

  /** `logContext` is a `FiberRef` shared by tests running in the same fiber, so a test that
    * asserts on annotations has to start from an empty one to see only its own. */
  private def ownRequest[A](zio: UIO[A]): UIO[A] =
    logContext.locally(LogContext.empty)(zio)

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
      test("evaluateBoolean folds a type mismatch into the request's error context") {
        ownRequest:
          for
            evaluator <- make
            program   <- evaluator.compile("token.role")
            _         <- program.evaluateBoolean(tokenContext)
            error     <- celError
          yield assertTrue(
            error.map(_.code).contains(Observability.CelEvaluationFailedCode),
            error.flatMap(_.description).exists(_.startsWith("`token.role`: ")),
            error.flatMap(_.description).exists(_.contains("instead of Boolean")),
          )
      },
      test("evaluateBoolean folds an evaluation error into the request's error context") {
        ownRequest:
          for
            evaluator <- make
            program   <- evaluator.compile("token.missing.deep.path == 'x'")
            result    <- program.evaluateBoolean(tokenContext)
            error     <- celError
          yield assertTrue(
            !result,
            error.map(_.code).contains(Observability.CelEvaluationFailedCode),
            error.flatMap(_.description).exists(_.startsWith("`token.missing.deep.path == 'x'`: ")),
          )
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
      test("evaluateString folds an evaluation error into the request's error context") {
        ownRequest:
          for
            evaluator <- make
            program   <- evaluator.compile("token.missing.deep.path")
            _         <- program.evaluateString(tokenContext)
            error     <- celError
          yield assertTrue(
            error.map(_.code).contains(Observability.CelEvaluationFailedCode),
            error.flatMap(_.description).exists(_.startsWith("`token.missing.deep.path`: ")),
          )
      },
      test("a later failure replaces the description left by an earlier one") {
        ownRequest:
          for
            evaluator <- make
            allow     <- evaluator.compile("token.missing.deep.path == 'x'")
            inject    <- evaluator.compile("token.other.deep.path")
            _         <- allow.evaluateBoolean(tokenContext)
            _         <- inject.evaluateString(tokenContext)
            error     <- celError
          yield assertTrue(error.flatMap(_.description).exists(_.startsWith("`token.other.deep.path`: ")))
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
