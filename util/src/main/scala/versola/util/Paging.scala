package versola.util

import zio.schema.Schema

/** How many records one call returns: 1 to [[PageLimit.Max]]. A request parameter outside it fails to decode, so it is a
  * bad request before anything reads it. */
object PageLimit:
  opaque type Type <: Int = Int

  val Max = 500
  val Default: Type = 100

  inline def apply(value: Int): Type = value

  given Schema[Type] = Schema.primitive[Int].transformOrFail(
    value => Either.cond(value >= 1 && value <= Max, value, s"limit is 1 to $Max"),
    Right(_),
  )

type PageLimit = PageLimit.Type

/** How many records a page skips: 0 or more. */
object PageOffset:
  opaque type Type <: Int = Int

  val First: Type = 0

  inline def apply(value: Int): Type = value

  given Schema[Type] = Schema.primitive[Int].transformOrFail(
    value => Either.cond(value >= 0, value, "offset is 0 or more"),
    Right(_),
  )

type PageOffset = PageOffset.Type
