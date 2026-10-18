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
    xa.connectMeasured("get-all-edges"):
      sql"""
        SELECT id, public_key_jwk, old_public_key_jwk, require_dpop_nonce,
               certificate_thumbprint, old_certificate_thumbprint
        FROM edges
      """.query[EdgeRecord].run()

  override def find(id: EdgeId): Task[Option[EdgeRecord]] =
    xa.connectMeasured("find-edge"):
      sql"""
        SELECT id, public_key_jwk, old_public_key_jwk, require_dpop_nonce,
               certificate_thumbprint, old_certificate_thumbprint
        FROM edges
        WHERE id = $id
      """.query[EdgeRecord].run().headOption

  override def createEdge(id: EdgeId, publicKeyJwk: Json.Obj, requireDpopNonce: Boolean): Task[Unit] =
    xa.connectMeasured("create-edge"):
      sql"""
        INSERT INTO edges (id, public_key_jwk, old_public_key_jwk, require_dpop_nonce)
        VALUES ($id, $publicKeyJwk, NULL, $requireDpopNonce)
      """.update.run()
    .unit

  override def rotateEdgeKey(id: EdgeId, newPublicKeyJwk: Json.Obj): Task[Unit] =
    xa.connectMeasured("rotate-edge-key"):
      sql"""
        UPDATE edges
        SET old_public_key_jwk = public_key_jwk,
            public_key_jwk = $newPublicKeyJwk
        WHERE id = $id
      """.update.run()
    .unit

  override def rotateCertificate(id: EdgeId, thumbprint: String): Task[Unit] =
    xa.connectMeasured("rotate-edge-certificate"):
      sql"""
        UPDATE edges
        SET old_certificate_thumbprint = certificate_thumbprint,
            certificate_thumbprint = $thumbprint
        WHERE id = $id
      """.update.run()
    .unit

  override def deleteOldCertificate(id: EdgeId): Task[Unit] =
    xa.connectMeasured("delete-old-edge-certificate"):
      sql"""
        UPDATE edges
        SET old_certificate_thumbprint = NULL
        WHERE id = $id
      """.update.run()
    .unit

  override def setRequireDpopNonce(id: EdgeId, requireDpopNonce: Boolean): Task[Unit] =
    xa.connectMeasured("set-edge-require-dpop-nonce"):
      sql"""
        UPDATE edges
        SET require_dpop_nonce = $requireDpopNonce
        WHERE id = $id
      """.update.run()
    .unit

  override def deleteOldEdgeKey(id: EdgeId): Task[Unit] =
    xa.connectMeasured("delete-old-edge-key"):
      sql"""
        UPDATE edges
        SET old_public_key_jwk = NULL
        WHERE id = $id
      """.update.run()
    .unit

  override def deleteEdge(id: EdgeId): Task[Unit] =
    xa.connectMeasured("delete-edge"):
      sql"""DELETE FROM edges WHERE id = $id""".update.run()
    .unit

object PostgresEdgeRepository:
  def live: ZLayer[TransactorZIO, Nothing, EdgeRepository] =
    ZLayer.fromFunction(PostgresEdgeRepository(_))
