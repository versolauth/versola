package versola.util

import zio.Chunk
import zio.prelude.Equal
import zio.schema.Schema

import java.nio.charset.StandardCharsets

trait ByteArrayNewType:
  opaque type Type <: Array[Byte] = Array[Byte]
  inline def apply(bytes: Array[Byte]): Type = bytes
  inline def wrapAll[F[_]](value: F[Array[Byte]]): F[Type] = value

  given Schema[Type] = Schema.chunk[Byte].transform(_.toArray, Chunk.fromArray)
  given Equal[Type] = (a, b) => java.util.Arrays.equals(a, b)

  inline def fromBase64Url(base64: String): Either[String, Type] =
    util.Try(java.util.Base64.getUrlDecoder.decode(base64)).toEither.left.map(_.getMessage)

  inline def fromString(str: String): Type = apply(str.getBytes(StandardCharsets.UTF_8))

object ByteArrayNewType:
  /** A value a client holds and presents back, which may carry a [[RoutingKey]]: the bytes
    * are what the server keys on, [[present]] and [[fromPresented]] translate to and from the
    * string a client sees, and [[issue]] derives the bytes for a fresh value under the cell
    * this deployment is, if it is one. See [[RoutingKey.Bytes]] for the two forms.
    */
  trait Routed extends ByteArrayNewType:
    inline def issue(cell: Option[RoutingKey.CellId], random: Array[Byte]): Type =
      apply(RoutingKey.Bytes.issue(cell, random))

    inline def fromPresented(presented: String): Either[String, Type] =
      RoutingKey.Bytes.parse(presented).map(apply)

    inline def present(value: Type): String =
      RoutingKey.Bytes.present(value)

  trait FixedLength(val ref: ByteArrayNewType, val length: Int):
    opaque type Type <: ref.Type = ref.Type

    inline def apply(bytes: Array[Byte]): Type = ref.apply(bytes)

    inline def fromBase64Url(base64: String): Either[String, Type] =
      ref.fromBase64Url(base64)

    given Schema[Type] = ref.given_Schema_Type
