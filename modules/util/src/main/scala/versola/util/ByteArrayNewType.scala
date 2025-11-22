package versola.util

import zio.Chunk
import zio.schema.Schema

trait ByteArrayNewType:
  opaque type Type <: Array[Byte] = Array[Byte]
  inline def apply(bytes: Array[Byte]): Type = bytes

  given Schema[Type] = Schema.chunk[Byte].transform(_.toArray, Chunk.fromArray)

  inline def fromBase64Url(base64: String): Type =
    java.util.Base64.getUrlDecoder.decode(base64)

object ByteArrayNewType:
  trait FixedLength(val ref: ByteArrayNewType, val length: Int):
    opaque type Type <: ref.Type = ref.Type

    inline def apply(bytes: Array[Byte]): Type = ref.apply(bytes)

    inline def fromBase64Url(base64: String): Type =
      ref.fromBase64Url(base64)

    given Schema[Type] = ref.given_Schema_Type