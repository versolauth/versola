package versola.util

import zio.prelude.Equal
import zio.schema.Schema

trait StringNewType:
  opaque type Type <: String = String
  inline def apply(value: String): Type = value

  given Equal[Type] = _ == _
  given Schema[Type] = Schema.primitive[String]


object StringNewType:
  trait Base64Url extends StringNewType:
    inline def fromBytes(bytes: Array[Byte]): Type =
      apply(Base64.urlEncode(bytes))
    