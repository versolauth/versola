package versola.util.cel

import dev.cel.common.{CelErrorCode, CelException}
import dev.cel.common.types.{CelType, SimpleType}
import dev.cel.compiler.CelCompilerFactory
import dev.cel.parser.CelStandardMacro
import dev.cel.runtime.{CelRuntime, CelRuntimeFactory}
import zio.{IO, Ref, UIO, ULayer, ZIO, ZLayer}

import scala.jdk.CollectionConverters.MapHasAsJava

trait CelEvaluator:
  def compile(expression: String): UIO[CelEvaluator.Program]
  def validate(expression: String, expectedType: Option[CelType] = None): IO[CelEvaluator.CompileError, CelEvaluator.Program]

object CelEvaluator:
  case class CompileError(expression: String, message: String)

  /** Why an expression produced no usable result. The distinction is the caller's whole
    * decision: [[DataMissing]] is an ordinary property of live request data and belongs on the
    * request's authorization path, [[Broken]] is an operator mistake and belongs in the 5xx
    * budget. Neither carries the expression or the underlying exception text: which rule is
    * configured on an endpoint is discoverable from the console once the request's endpoint is
    * known, and the exception text varies in shape with the value that tripped it. */
  enum EvaluationError:
    /** The expression read a claim, field or key the request doesn't carry -- CEL's
      * `ATTRIBUTE_NOT_FOUND`. Expected in production: tokens for different users legitimately
      * carry different claims. The rule cannot be satisfied, which is not the same as the rule
      * being wrong, so the request is refused rather than errored. An expression that should
      * tolerate absence can say so with `has(...)`. */
    case DataMissing

    /** The expression could not be evaluated at all: any other CEL error code (a division by
      * zero, a numeric overflow, an unresolved overload), a boolean rule that returned
      * something other than a boolean, or an expression that failed to compile. The
      * configuration is wrong, not the request. */
    case Broken

  trait Program:
    def evaluateBoolean(context: Map[String, AnyRef]): IO[EvaluationError, Boolean]
    def evaluateString(context: Map[String, AnyRef]): IO[EvaluationError, Option[String]]

  val live: ULayer[CelEvaluator] =
    ZLayer:
      Ref.make(Map.empty[String, Either[CompileError, Program]]).map(Impl(_))

  private val compiler =
    CelCompilerFactory.standardCelCompilerBuilder()
      // `has(user.plan)` is how an expression guards a claim that only some tokens carry;
      // without the macros an absent claim can only ever be an [[EvaluationError.DataMissing]].
      .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
      .addVar("token", SimpleType.DYN)
      .addVar("user", SimpleType.DYN)
      .addVar("request", SimpleType.DYN)
      .build()

  private val runtime: CelRuntime =
    CelRuntimeFactory.standardCelRuntimeBuilder().build()

  /** Stands in for an expression that didn't compile, so a configuration mistake surfaces the
    * same way at every call site as one that only shows up at evaluation time. Evaluating to
    * `false`/no value instead would let a rule that doesn't compile pass silently: a step-up
    * condition would stop requiring step-up, an inject rule would stop injecting. */
  private val FailSafe: Program = new Program:
    override def evaluateBoolean(context: Map[String, AnyRef]): IO[EvaluationError, Boolean] =
      ZIO.fail(EvaluationError.Broken)
    override def evaluateString(context: Map[String, AnyRef]): IO[EvaluationError, Option[String]] =
      ZIO.fail(EvaluationError.Broken)

  class Impl(cache: Ref[Map[String, Either[CompileError, Program]]]) extends CelEvaluator:
    override def compile(expression: String): UIO[Program] =
      compileCached(expression, None).flatMap:
        case Right(program) => ZIO.succeed(program)
        case Left(err) =>
          ZIO.logWarning(s"CEL compilation failed for trusted expression '${err.expression}': ${err.message}")
            .as(FailSafe)

    override def validate(expression: String, expectedType: Option[CelType]): IO[CompileError, Program] =
      compileCached(expression, expectedType).flatMap:
        case Right(program) => ZIO.succeed(program)
        case Left(err)      => ZIO.fail(err)

    private def compileCached(expression: String, expectedType: Option[CelType]): UIO[Either[CompileError, Program]] =
      val key = expectedType.fold(expression)(t => s"$expression:$t")
      cache.get.map(_.get(key)).flatMap:
        case Some(result) => ZIO.succeed(result)
        case None =>
          compileProgram(expression, expectedType)
            .tap(result => cache.update(_.updated(key, result)))

    private def compileProgram(expression: String, expectedType: Option[CelType]): UIO[Either[CompileError, Program]] =
      ZIO.attempt:
        val ast = compiler.compile(expression).getAst
        // Returns true when the expected type was accepted only because the compiler
        // inferred DYN (i.e. the type guarantee could not be statically proven).
        val dynAccepted = expectedType.exists: t =>
          val actualType = ast.getType(ast.getExpr().id()).orElse(SimpleType.DYN)
          if actualType != t && actualType != SimpleType.DYN then
            throw new IllegalArgumentException(s"Expected return type $t but got $actualType")
          actualType == SimpleType.DYN
        (ProgramImpl(runtime.createProgram(ast)): Program, dynAccepted)
      .either
      .flatMap:
        case Right((program, true)) =>
          ZIO.logWarning(
            s"CEL expression '$expression' has a dynamic return type; " +
            s"the expected type could not be verified at compile time. " +
            s"A wrong-typed result will silently evaluate to false at runtime."
          ).as(Right(program))
        case Right((program, false)) =>
          ZIO.succeed(Right(program))
        case Left(ex) =>
          ZIO.succeed(Left(CompileError(expression, Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))))

  /** A failure is returned to the caller rather than degraded to `false`/no value here: the
    * caller is the only one that knows what an unevaluated expression means for the request it
    * is handling (a denial, a step-up challenge, a request that must not be forwarded), and it
    * is the only one that can record the outcome against that request. */
  private class ProgramImpl(program: CelRuntime.Program) extends Program:
    override def evaluateBoolean(context: Map[String, AnyRef]): IO[EvaluationError, Boolean] =
      evaluate(context).flatMap:
        case b: java.lang.Boolean => ZIO.succeed(b.booleanValue)
        case _                    => ZIO.fail(EvaluationError.Broken)

    override def evaluateString(context: Map[String, AnyRef]): IO[EvaluationError, Option[String]] =
      evaluate(context).map:
        case null      => None
        case s: String => Some(s)
        case other     => Some(other.toString)

    private def evaluate(context: Map[String, AnyRef]): IO[EvaluationError, AnyRef] =
      ZIO.attempt(program.eval(context.asJava)).mapError:
        case ex: CelException if ex.getErrorCode == CelErrorCode.ATTRIBUTE_NOT_FOUND => EvaluationError.DataMissing
        case _                                                                      => EvaluationError.Broken
