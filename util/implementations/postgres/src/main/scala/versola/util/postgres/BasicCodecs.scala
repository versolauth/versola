package versola.util.postgres

import com.augustnagro.magnum.DbCodec
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.json.JsonBDbCodec
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec, json}
import zio.NonEmptyChunk
import zio.json.*
import zio.json.ast.Json
import zio.prelude.NonEmptySet

import java.sql.Connection
import java.util.UUID
import scala.annotation.targetName
import scala.reflect.ClassTag

trait BasicCodecs:
  def jsonBCodec[A: {JsonDecoder, JsonEncoder}]: json.JsonBDbCodec[A] =
    new json.JsonBDbCodec[A]:
      override def encode(a: A): String =
        a.toJson

      override def decode(json: String): A =
        json.fromJson[A].fold(m => throw IllegalStateException(m), identity)

  def jsonCodec[A: {JsonDecoder as enc, JsonEncoder as dec}]: json.JsonDbCodec[A] =
    new json.JsonDbCodec[A]:
      override def encode(a: A): String =
        a.toJson

      override def decode(json: String): A =
        json.fromJson[A].fold(m => throw IllegalStateException(m), identity)

  given [A <: String] => SqlArrayCodec[A] =
    SqlArrayCodec.StringSqlArrayCodec.asInstanceOf[SqlArrayCodec[A]]

  given [A: {DbCodec, SqlArrayCodec}] => DbCodec[NonEmptySet[A]] =
    PgCodec.SeqCodec[A].biMap(it => NonEmptySet.fromIterableOption(it).get, _.toSeq)

  given [A: {DbCodec, SqlArrayCodec}] => DbCodec[NonEmptyChunk[A]] =
    PgCodec.SeqCodec[A].biMap(it => NonEmptyChunk.fromIterableOption(it).get, _.toSeq)

  given [A: {DbCodec, SqlArrayCodec}] => DbCodec[Set[A]] =
    PgCodec.SeqCodec[A].biMap(_.toSet, _.toSeq)

  given DbCodec[Map[String, String]] = jsonBCodec[Json.Obj]
    .biMap(
      _.toMap.map((k, v) => (k, v.asString.get)),
      it => Json.Obj(it.map((k, v) => k -> Json.Str(v)).toSeq*),
    )


  given UUIDSqlArrayCodec: SqlArrayCodec[UUID] = new SqlArrayCodec[UUID]:
    val jdbcTypeName: String = "uuid"

    def readArray(array: Object): Array[UUID] =
      array match
        case strings: Array[UUID @unchecked] => strings

    def toArrayObj(entity: UUID): Object = entity

  @targetName("uuidSubtypeSqlArrayCodec")
  given [A <: UUID] => SqlArrayCodec[A] = UUIDSqlArrayCodec.asInstanceOf[SqlArrayCodec[A]]


  def dbCodecFromJsonCodec[A <: Product: JsonCodec]: JsonBDbCodec[A] =
    val codec = jsonBCodec[Json.Obj]
    new JsonBDbCodec[A]:
      override def encode(a: A): String =
        val ast = a.toJsonAST.toOption.flatMap(_.asObject).getOrElse(throw IllegalStateException("Invalid claim record"))
        codec.encode(ast)

      override def decode(json: String): A =
        codec.decode(json).as[A].getOrElse(throw IllegalStateException("Invalid claim record"))
  end dbCodecFromJsonCodec


  given [A <: Product: {JsonBDbCodec as codec, ClassTag}] => SqlArrayCodec[A] =
    new SqlArrayCodec[A]:
      override def jdbcTypeName: String = "jsonb"

      override def readArray(array: Object): Array[A] =
        array.asInstanceOf[Array[String]].map(codec.decode)

      override def toArrayObj(entity: A): AnyRef =
        codec.encode(entity)

  given [A: {DbCodec, SqlArrayCodec}] => DbCodec[Vector[A]] = PgCodec.VectorCodec
  given [A: {DbCodec, SqlArrayCodec}] => DbCodec[List[A]] = PgCodec.ListCodec

  given [A: SqlArrayCodec] => SqlArrayCodec[List[A]] = SqlArrayCodec.ListSqlArrayCodec
  given [A: SqlArrayCodec] => SqlArrayCodec[Vector[A]] = SqlArrayCodec.VectorSqlArrayCodec

  extension (xa: TransactorZIO)
    def repeatableRead: TransactorZIO =
      xa.withConnectionConfig(_.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ))

    def serializable: TransactorZIO =
      xa.withConnectionConfig(_.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE))
