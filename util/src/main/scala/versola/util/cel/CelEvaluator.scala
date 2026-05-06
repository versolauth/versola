package versola.util.cel

import dev.cel.common.types.SimpleType
import dev.cel.compiler.CelCompilerFactory
import dev.cel.runtime.{CelRuntime, CelRuntimeFactory}
import zio.{IO, Ref, UIO, ULayer, ZIO, ZLayer}

import scala.jdk.CollectionConverters.MapHasAsJava

trait CelEvaluator:
  def compile(expression: String): UIO[CelEvaluator.Program]
  def validate(expression: String): IO[CelEvaluator.CompileError, CelEvaluator.Program]

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
      compileCached(expression).flatMap:
        case Right(program) => ZIO.succeed(program)
        case Left(err) =>
          ZIO.logWarning(s"CEL compilation failed for trusted expression '${err.expression}': ${err.message}")
            .as(FailSafe)

    override def validate(expression: String): IO[CompileError, Program] =
      compileCached(expression).flatMap:
        case Right(program) => ZIO.succeed(program)
        case Left(err)      => ZIO.fail(err)

    private def compileCached(expression: String): UIO[Either[CompileError, Program]] =
      cache.get.map(_.get(expression)).flatMap:
        case Some(result) => ZIO.succeed(result)
        case None =>
          compileProgram(expression)
            .tap(result => cache.update(_.updated(expression, result)))

    private def compileProgram(expression: String): UIO[Either[CompileError, Program]] =
      ZIO.attempt:
        val ast = compiler.compile(expression).getAst
        ProgramImpl(runtime.createProgram(ast))
      .either
      .map(_.left.map(ex => CompileError(expression, Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))))

  private class ProgramImpl(program: CelRuntime.Program) extends Program:
    override def evaluateBoolean(context: Map[String, AnyRef]): UIO[Boolean] =
      ZIO.attempt(program.eval(context.asJava))
        .map:
          case b: java.lang.Boolean => b.booleanValue
          case _                    => false
        .catchAll: ex =>
          ZIO.logWarning(s"CEL evaluation failed: ${ex.getMessage}").as(false)

    override def evaluateString(context: Map[String, AnyRef]): UIO[Option[String]] =
      ZIO.attempt(program.eval(context.asJava))
        .map:
          case null         => None
          case s: String    => Some(s)
          case other        => Some(other.toString)
        .catchAll: ex =>
          ZIO.logWarning(s"CEL evaluation failed: ${ex.getMessage}").as(None)
