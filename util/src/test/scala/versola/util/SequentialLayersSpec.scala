package versola.util

import versola.util.SequentialLayers.*
import zio.*
import zio.test.*

object SequentialLayersSpec extends ZIOSpecDefault:

  case class A(n: Int)
  case class B(n: Int)
  case class C(n: Int)

  def spec = suite("SequentialLayers")(
    test(">++> gives the right side the left side's output and returns both") {
      val a = ZLayer.succeed(A(1))
      val b = ZLayer.fromFunction((a: A) => B(a.n + 1))
      val c = ZLayer.fromFunction((a: A, b: B) => C(a.n + b.n))
      for
        env <- ZIO.scoped((a >++> b >++> c).build)
      yield assertTrue(env.get[A] == A(1), env.get[B] == B(2), env.get[C] == C(3))
    },
    test(">++> takes the right side's own requirements from the surrounding environment") {
      val a = ZLayer.succeed(A(1))
      val b = ZLayer.fromFunction((a: A, c: C) => B(a.n + c.n))
      for
        env <- ZIO.scoped((a >++> b).build).provideSomeEnvironment[Scope]((s: ZEnvironment[Scope]) => s.unionAll(ZEnvironment(C(41))))
      yield assertTrue(env.get[B] == B(42))
    },
    test("layers are acquired in order and released in the opposite order") {
      for
        log <- Ref.make(Vector.empty[String])
        layer = (name: String) => ZLayer.scoped(ZIO.acquireRelease(log.update(_ :+ s"+$name"))(_ => log.update(_ :+ s"-$name")))
        _ <- ZIO.scoped((layer("1") >++> layer("2") >++> layer("3")).build)
        events <- log.get
      yield assertTrue(events == Vector("+1", "+2", "+3", "-3", "-2", "-1"))
    },
    // The point of the operator: a failure travels one path, so its cause stays the size of the original
    // however deep the chain, and nothing after the failing layer is built.
    test("a failing layer deep in a long chain fails with its own cause and builds nothing after it") {
      for
        built <- Ref.make(0)
        step = (fail: Boolean) =>
          ZLayer.fromZIO[Any, Throwable, Int]:
            built.update(_ + 1) *> (if fail then ZIO.fail(java.io.FileNotFoundException("forms/common.css")) else ZIO.succeed(1))
        chain = (1 to 120).foldLeft(ZLayer.succeed(0): ZLayer[Any, Throwable, Int])((acc, i) => acc >++> step(i == 5))
        exit <- ZIO.scoped(chain.build).exit
        count <- built.get
      yield assertTrue(
        exit.causeOption.exists(_.size == 1),
        exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[java.io.FileNotFoundException]),
        count == 5,
      )
    },
  ) @@ TestAspect.timeout(30.seconds)
