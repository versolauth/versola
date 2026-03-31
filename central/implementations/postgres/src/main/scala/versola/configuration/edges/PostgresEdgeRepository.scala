package versola.configuration.edges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.{EdgeId, EdgeRecord, EdgeRepository}
import versola.util.postgres.BasicCodecs
import zio.json.ast.Json
import zio.{Task, ZLayer}

class PostgresEdgeRepository(xa: TransactorZIO) extends EdgeRepository, BasicCodecs:

  given DbCodec[EdgeId] = DbCodec.StringCodec.biMap(EdgeId(_), identity[String])
  given DbCodec[Json.Obj] = jsonCodec[Json.Obj]
  given DbCodec[EdgeRecord] = DbCodec.derived

  override def getAll: Task[Vector[EdgeRecord]] =
    xa.connect:
      sql"""
        SELECT id, public_key_jwk, old_public_key_jwk
        FROM edges
      """.query[EdgeRecord].run()

  override def find(id: EdgeId): Task[Option[EdgeRecord]] =
    xa.connect:
      sql"""
        SELECT id, public_key_jwk, old_public_key_jwk
        FROM edges
        WHERE id = $id
      """.query[EdgeRecord].run().headOption

  override def createEdge(id: EdgeId, publicKeyJwk: Json.Obj): Task[Unit] =
    xa.connect:
      sql"""
        INSERT INTO edges (id, public_key_jwk, old_public_key_jwk)
        VALUES ($id, $publicKeyJwk, NULL)
      """.update.run()
    .unit

  override def rotateEdgeKey(id: EdgeId, newPublicKeyJwk: Json.Obj): Task[Unit] =
    xa.connect:
      sql"""
        UPDATE edges
        SET old_public_key_jwk = public_key_jwk,
            public_key_jwk = $newPublicKeyJwk
        WHERE id = $id
      """.update.run()
    .unit

  override def deleteOldEdgeKey(id: EdgeId): Task[Unit] =
    xa.connect:
      sql"""
        UPDATE edges
        SET old_public_key_jwk = NULL
        WHERE id = $id
      """.update.run()
    .unit

  override def deleteEdge(id: EdgeId): Task[Unit] =
    xa.connect:
      sql"""DELETE FROM edges WHERE id = $id""".update.run()
    .unit

object PostgresEdgeRepository:
  def live: ZLayer[TransactorZIO, Nothing, EdgeRepository] =
    ZLayer.fromFunction(PostgresEdgeRepository(_))
