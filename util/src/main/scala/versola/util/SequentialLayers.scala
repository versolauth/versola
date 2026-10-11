package versola.util

import zio.*

/** `>+>` that builds its two sides one after the other, not in parallel.
  *
  * ZIO's `>+>` is `self ++ (self >>> that)`, and `++` builds both sides in parallel. When a layer
  * fails, the failure reaches every enclosing `>+>` through both of its sides, each combined into
  * the parent's cause (`Cause.Both`) with a stack trace appended on the way: the cause roughly
  * doubles per level. Our `dependencies` chains are 30-60 levels deep, so a failure in an early
  * layer (a wrong database password, a missing resource) grows one `Cause` until the heap is full:
  * `OutOfMemoryError` within seconds, and a heap dump that does not fit the `diagnostics` volume,
  * instead of one error line. The same shape is zio/zio#4503 and #7499.
  *
  * Here the right side is built only after the left one succeeded, so a failure travels one path
  * and the cause stays the size of the original. Nothing is lost: in a `>+>` chain the right side
  * needs the left one's output, so the two never overlapped anyway.
  */
object SequentialLayers:

  extension [RIn, E, ROut](self: ZLayer[RIn, E, ROut])
    def >++>[RIn2, E1 >: E, ROut2](that: => ZLayer[ROut & RIn2, E1, ROut2]): ZLayer[RIn & RIn2, E1, ROut & ROut2] =
      ZLayer.scopedEnvironment[RIn & RIn2]:
        for
          input <- ZIO.environment[RIn & RIn2]
          left <- self.build.provideSomeEnvironment[Scope]((scope: ZEnvironment[Scope]) => scope.unionAll(input))
          right <- that.build.provideSomeEnvironment[Scope]((scope: ZEnvironment[Scope]) => scope.unionAll(input).unionAll(left))
        yield left.unionAll(right)
