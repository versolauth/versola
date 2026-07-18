package versola.configuration.jwks

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.jwks.{JwksRecord, JwksRepository}
import versola.util.postgres.BasicCodecs
import zio.json.ast.Json
import zio.{Task, ZLayer}

class PostgresJwksRepository(xa: TransactorZIO) extends JwksRepository, BasicCodecs:

  given DbCodec[Json.Obj] = jsonCodec[Json.Obj]
  given DbCodec[JwksRecord] = DbCodec.derived

  override def getAll: Task[Vector[JwksRecord]] =
    xa.connectMeasured("get-all-jwks"):
      sql"""
        SELECT kid, jwk
        FROM jwks
      """.query[JwksRecord].run()

  override def find(kid: String): Task[Option[JwksRecord]] =
    xa.connectMeasured("find-jwks"):
      sql"""
        SELECT kid, jwk
        FROM jwks
        WHERE kid = $kid
      """.query[JwksRecord].run().headOption

  override def create(kid: String, jwk: Json.Obj): Task[Unit] =
    xa.connectMeasured("create-jwks"):
      sql"""
        INSERT INTO jwks (kid, jwk)
        VALUES ($kid, $jwk)
      """.update.run()
    .unit

  override def update(kid: String, jwk: Json.Obj): Task[Unit] =
    xa.connectMeasured("update-jwks"):
      sql"""
        UPDATE jwks
        SET jwk = $jwk
        WHERE kid = $kid
      """.update.run()
    .unit

  override def delete(kid: String): Task[Unit] =
    xa.connectMeasured("delete-jwks"):
      sql"""
        DELETE FROM jwks
        WHERE kid = $kid
      """.update.run()
    .unit

object PostgresJwksRepository:
  def live: ZLayer[TransactorZIO, Nothing, JwksRepository] =
    ZLayer.fromFunction(PostgresJwksRepository(_))
