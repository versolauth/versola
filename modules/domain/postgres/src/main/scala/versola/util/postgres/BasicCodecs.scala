package versola.util.postgres

import com.augustnagro.magnum.DbCodec
import com.augustnagro.magnum.pg.json.JsonBDbCodec
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec}
import zio.json.*
import zio.prelude.NonEmptySet

trait BasicCodecs:
  def optionalJsonCodec[A: {JsonDecoder as enc, JsonEncoder as dec}]: JsonBDbCodec[Option[A]] =
    new JsonBDbCodec[Option[A]]:
      override def encode(a: Option[A]) =
        a.fold("null")(_.toJson)

      override def decode(json: String) =
        Option(json).map(_.fromJson[A].fold(m => throw IllegalStateException(m), identity))

  def jsonCodec[A: {JsonDecoder as enc, JsonEncoder as dec}]: JsonBDbCodec[A] =
    new JsonBDbCodec[A]:
      override def encode(a: A) =
        a.toJson

      override def decode(json: String) =
        json.fromJson[A].fold(m => throw IllegalStateException(m), identity)

  inline given [A] => DbCodec[A] =
    scala.compiletime.summonFrom {
      case ev: <:<[A, String] => DbCodec.StringCodec.biMap(_.asInstanceOf[A], ev(_))
      case ev: <:<[A, Long] => DbCodec.LongCodec.biMap(_.asInstanceOf[A], ev(_))
      case ev: <:<[A, Int] => DbCodec.IntCodec.biMap(_.asInstanceOf[A], ev(_))
      case ev: <:<[A, Double] => DbCodec.DoubleCodec.biMap(_.asInstanceOf[A], ev(_))
      case ev: <:<[A, Boolean] => DbCodec.BooleanCodec.biMap(_.asInstanceOf[A], ev(_))
      case ev: <:<[A, Array[Byte]] => DbCodec.ByteArrayCodec.biMap(_.asInstanceOf[A], ev(_))
    }

  given [A: {DbCodec, SqlArrayCodec}] => DbCodec[Set[A]] =
    PgCodec.SeqCodec[A].biMap(_.toSet, _.toSeq)

  given [A <: String] => SqlArrayCodec[A] =
    SqlArrayCodec.StringSqlArrayCodec.asInstanceOf[SqlArrayCodec[A]]

  given [A: {DbCodec, SqlArrayCodec}] => DbCodec[NonEmptySet[A]] =
    PgCodec.SeqCodec[A].biMap(it => NonEmptySet.fromIterableOption(it).get, _.toSeq)
