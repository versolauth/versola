package versola.util.cel

import dev.cel.common.types.{CelType, SimpleType}
import dev.cel.compiler.CelCompilerFactory
import dev.cel.runtime.{CelRuntime, CelRuntimeFactory}
import versola.util.http.Observability
import zio.{IO, Ref, UIO, ULayer, ZIO, ZLayer}

import scala.jdk.CollectionConverters.MapHasAsJava

trait CelEvaluator:
  def compile(expression: String): UIO[CelEvaluator.Program]
  def validate(expression: String, expectedType: Option[CelType] = None): IO[CelEvaluator.CompileError, CelEvaluator.Program]

object CelEvaluator:
  case class CompileError(expression: String, message: String)

  trait Program:
    def evaluateBoolean(context: Map[String, AnyRef]): UIO[Boolean]
    def evaluateString(context: Map[String, AnyRef]): UIO[Option[String]]

  val live: ULayer[CelEvaluator] =
    ZLayer:
      Ref.make(Map.empty[String, Either[CompileError, Program]]).map(Impl(_))

  private val compiler =
    CelCompilerFactory.standardCelCompilerBuilder()
      .addVar("token", SimpleType.DYN)
      .addVar("user", SimpleType.DYN)
      .addVar("request", SimpleType.DYN)
      .build()

  private val runtime: CelRuntime =
    CelRuntimeFactory.standardCelRuntimeBuilder().build()

  private val FailSafe: Program = new Program:
    override def evaluateBoolean(context: Map[String, AnyRef]): UIO[Boolean] = ZIO.succeed(false)
    override def evaluateString(context: Map[String, AnyRef]): UIO[Option[String]] = ZIO.none

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

  /** A failed evaluation is reported into the log context of the request that triggered it
    * rather than as a warning line of its own: the degraded result (`false`, or no injected
    * value) is what the caller acts on, and it is only interpretable next to the outcome that
    * request ended with. The report names neither the expression nor the underlying exception --
    * the expression is discoverable from the endpoint's configured rules in the console once
    * the request's endpoint is known, and the exception text can vary in shape depending on
    * the value that tripped it. */
  private class ProgramImpl(program: CelRuntime.Program) extends Program:
    override def evaluateBoolean(context: Map[String, AnyRef]): UIO[Boolean] =
      ZIO.attempt(program.eval(context.asJava))
        .flatMap:
          case b: java.lang.Boolean => ZIO.succeed(b.booleanValue)
          case _                    => Observability.annotateCelFailure("non-boolean result, treated as false").as(false)
        .catchAll: _ =>
          Observability.annotateCelFailure("evaluation failed, treated as false").as(false)

    override def evaluateString(context: Map[String, AnyRef]): UIO[Option[String]] =
      ZIO.attempt(program.eval(context.asJava))
        .map:
          case null         => None
          case s: String    => Some(s)
          case other        => Some(other.toString)
        .catchAll: _ =>
          Observability.annotateCelFailure("evaluation failed, no value injected").as(None)
