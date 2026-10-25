package versola.configuration.edges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.{EdgeGroupId, EdgeGroupKind, EdgeId, EdgeRecord, EdgeRepository}
import versola.util.http.{AccessLogPolicy, OverloadPolicy}
import versola.util.postgres.BasicCodecs
import zio.json.ast.Json
import zio.{Task, ZLayer}

class PostgresEdgeRepository(xa: TransactorZIO) extends EdgeRepository, BasicCodecs:

  given DbCodec[EdgeId] = DbCodec.StringCodec.biMap(EdgeId(_), identity[String])
  given DbCodec[Json.Obj] = jsonCodec[Json.Obj]
  given DbCodec[OverloadPolicy] = jsonCodec[OverloadPolicy]
  given DbCodec[AccessLogPolicy] = jsonCodec[AccessLogPolicy]
  given DbCodec[EdgeGroupId] = DbCodec.StringCodec.biMap(EdgeGroupId(_), identity[String])
  given DbCodec[EdgeGroupKind] =
    DbCodec.StringCodec.biMap(code => EdgeGroupKind.from(code).fold(message => throw IllegalStateException(message), identity), _.code)
  given DbCodec[EdgeRecord] = DbCodec.derived

  override def getAll: Task[Vector[EdgeRecord]] =
    xa.connectMeasured("get-all-edges"):
      sql"""
        SELECT e.id, e.public_key_jwk, e.old_public_key_jwk, e.require_dpop_nonce, e.overload_policy, e.access_log_policy, e.group_id, g.kind
        FROM edges e
        JOIN edge_groups g ON g.id = e.group_id
      """.query[EdgeRecord].run()

  override def find(id: EdgeId): Task[Option[EdgeRecord]] =
    xa.connectMeasured("find-edge"):
      sql"""
        SELECT e.id, e.public_key_jwk, e.old_public_key_jwk, e.require_dpop_nonce, e.overload_policy, e.access_log_policy, e.group_id, g.kind
        FROM edges e
        JOIN edge_groups g ON g.id = e.group_id
        WHERE e.id = $id
      """.query[EdgeRecord].run().headOption

  override def createEdge(id: EdgeId, publicKeyJwk: Json.Obj, requireDpopNonce: Boolean, groupId: EdgeGroupId): Task[Unit] =
    xa.connectMeasured("create-edge"):
      sql"""
        INSERT INTO edges (id, public_key_jwk, old_public_key_jwk, require_dpop_nonce, group_id)
        VALUES ($id, $publicKeyJwk, NULL, $requireDpopNonce, $groupId)
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

  override def setRequireDpopNonce(id: EdgeId, requireDpopNonce: Boolean): Task[Unit] =
    xa.connectMeasured("set-edge-require-dpop-nonce"):
      sql"""
        UPDATE edges
        SET require_dpop_nonce = $requireDpopNonce
        WHERE id = $id
      """.update.run()
    .unit

  override def setOverloadPolicy(id: EdgeId, policy: Option[OverloadPolicy]): Task[Unit] =
    xa.connectMeasured("set-edge-overload-policy"):
      sql"""
        UPDATE edges
        SET overload_policy = $policy
        WHERE id = $id
      """.update.run()
    .unit

  override def setAccessLogPolicy(id: EdgeId, policy: Option[AccessLogPolicy]): Task[Unit] =
    xa.connectMeasured("set-edge-access-log-policy"):
      sql"""
        UPDATE edges
        SET access_log_policy = $policy
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
