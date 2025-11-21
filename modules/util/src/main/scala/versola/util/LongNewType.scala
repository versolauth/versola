package versola.util

import zio.schema.Schema

trait LongNewType:
  opaque type Type <: Long = Long
  inline def apply(value: Long): Type = value
  
  given Schema[Type] = Schema.primitive[Long]

