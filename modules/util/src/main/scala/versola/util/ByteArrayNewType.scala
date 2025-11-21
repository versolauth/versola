package versola.util

import zio.Chunk
import zio.schema.Schema

trait ByteArrayNewType:
  opaque type Type <: Array[Byte] = Array[Byte]
  inline def apply(bytes: Array[Byte]): Type = bytes

  given Schema[Type] = Schema.chunk[Byte].transform(_.toArray, Chunk.fromArray)

  extension (bytes: Type)
    def toBase64Url: String = java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  def fromBase64Url(base64: String): Type =
    java.util.Base64.getUrlDecoder.decode(base64)
