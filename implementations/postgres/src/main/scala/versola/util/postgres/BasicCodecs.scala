package versola.util.postgres

import com.augustnagro.magnum.DbCodec
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec, json}
import zio.json.*
import zio.prelude.NonEmptySet

trait BasicCodecs:
  def jsonCodec[A: {JsonDecoder as enc, JsonEncoder as dec}]: json.JsonDbCodec[A] =
    new json.JsonDbCodec[A]:
      override def encode(a: A) =
        a.toJson

      override def decode(json: String) =
        json.fromJson[A].fold(m => throw IllegalStateException(m), identity)

  given [A: {DbCodec, SqlArrayCodec}] => DbCodec[Set[A]] =
    PgCodec.SeqCodec[A].biMap(_.toSet, _.toSeq)

  given [A <: String] => SqlArrayCodec[A] =
    SqlArrayCodec.StringSqlArrayCodec.asInstanceOf[SqlArrayCodec[A]]

  given [A: {DbCodec, SqlArrayCodec}] => DbCodec[NonEmptySet[A]] =
    PgCodec.SeqCodec[A].biMap(it => NonEmptySet.fromIterableOption(it).get, _.toSeq)
