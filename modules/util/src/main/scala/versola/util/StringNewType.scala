package versola.util

import zio.schema.Schema

trait StringNewType:
  opaque type Type <: String = String
  inline def apply(value: String): Type = value
  
  given Schema[Type] = Schema.primitive[String]
