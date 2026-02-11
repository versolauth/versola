package versola.util

import zio.Chunk
import zio.prelude.Equal
import zio.schema.Schema

trait ByteArrayNewType:
  opaque type Type <: Array[Byte] = Array[Byte]
  inline def apply(bytes: Array[Byte]): Type = bytes
  inline def wrapAll[F[_]](value: F[Array[Byte]]): F[Type] = value

  given Schema[Type] = Schema.chunk[Byte].transform(_.toArray, Chunk.fromArray)
  given Equal[Type] = (a, b) => java.util.Arrays.equals(a, b)

  inline def fromBase64Url(base64: String): Either[String, Type] =
    util.Try(java.util.Base64.getUrlDecoder.decode(base64)).toEither.left.map(_.getMessage)

object ByteArrayNewType:
  trait FixedLength(val ref: ByteArrayNewType, val length: Int):
    opaque type Type <: ref.Type = ref.Type

    inline def apply(bytes: Array[Byte]): Type = ref.apply(bytes)

    inline def fromBase64Url(base64: String): Either[String, Type] =
      ref.fromBase64Url(base64)

    given Schema[Type] = ref.given_Schema_Type