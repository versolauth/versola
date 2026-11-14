package versola.configuration.edges

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.{EdgeGroupId, EdgeGroupKind, EdgeId, EdgeRecord, EdgeRepository, PolicyPatchError, PolicySectionMeta}
import versola.central.configuration.partners.{PartnerEdgeLimitReached, PartnerId, PartnerLimits}
import versola.util.http.{AccessLogPolicy, OverloadPolicy, RateLimitPolicies}
import versola.util.postgres.BasicCodecs
import zio.json.ast.Json
import zio.{IO, Task, ZLayer}

class PostgresEdgeRepository(xa: TransactorZIO) extends EdgeRepository, BasicCodecs:

  given DbCodec[EdgeId] = DbCodec.StringCodec.biMap(EdgeId(_), identity[String])
  given DbCodec[PartnerId] = DbCodec.StringCodec.biMap(PartnerId(_), identity[String])
  given DbCodec[PartnerLimits] = jsonBCodec[PartnerLimits]
  given DbCodec[Json.Obj] = jsonCodec[Json.Obj]
  given DbCodec[OverloadPolicy] = jsonCodec[OverloadPolicy]
  given DbCodec[AccessLogPolicy] = jsonCodec[AccessLogPolicy]
  given DbCodec[RateLimitPolicies] = jsonCodec[RateLimitPolicies]
  given policyMetaCodec: DbCodec[Map[String, PolicySectionMeta]] = jsonCodec[Map[String, PolicySectionMeta]]
  given DbCodec[EdgeGroupId] = DbCodec.StringCodec.biMap(EdgeGroupId(_), identity[String])
  given DbCodec[EdgeGroupKind] =
    DbCodec.StringCodec.biMap(code => EdgeGroupKind.from(code).fold(message => throw IllegalStateException(message), identity), _.code)
  given DbCodec[EdgeRecord] = DbCodec.derived

  override def getAll: Task[Vector[EdgeRecord]] =
    xa.connectMeasured("get-all-edges"):
      sql"""
        SELECT e.id, e.public_key_jwk, e.old_public_key_jwk, e.require_dpop_nonce, e.overload_policy, e.access_log_policy, e.rate_limit_policies, e.policy_meta, e.group_id, g.kind, e.public_url
        FROM edges e
        JOIN edge_groups g ON g.id = e.group_id
      """.query[EdgeRecord].run()

  override def find(id: EdgeId): Task[Option[EdgeRecord]] =
    xa.connectMeasured("find-edge"):
      sql"""
        SELECT e.id, e.public_key_jwk, e.old_public_key_jwk, e.require_dpop_nonce, e.overload_policy, e.access_log_policy, e.rate_limit_policies, e.policy_meta, e.group_id, g.kind, e.public_url
        FROM edges e
        JOIN edge_groups g ON g.id = e.group_id
        WHERE e.id = $id
      """.query[EdgeRecord].run().headOption

  override def createEdge(
      id: EdgeId,
      publicKeyJwk: Option[Json.Obj],
      requireDpopNonce: Boolean,
      groupId: EdgeGroupId,
      publicUrl: Option[String],
  ): IO[PartnerEdgeLimitReached | Throwable, Unit] =
    xa.transactMeasured("create-edge"):
      // The edges of a partner's group are counted against the partner's limit under the partner's row lock (`FOR NO KEY UPDATE`), so
      // two creations run one after the other and the second counts the first. The decision is made here, not in the database.
      val owner = sql"SELECT id FROM partners WHERE group_id = $groupId FOR NO KEY UPDATE".query[PartnerId].run().headOption
      owner.foreach: partnerId =>
        val limits = sql"SELECT limits FROM partners WHERE id = $partnerId".query[PartnerLimits].run().head
        val existing = sql"SELECT COUNT(*) FROM edges WHERE group_id = $groupId".query[Long].run().head
        if existing >= limits.maxEdges then throw PartnerEdgeLimitReached(partnerId, limits.maxEdges)
      sql"""
        INSERT INTO edges (id, public_key_jwk, old_public_key_jwk, require_dpop_nonce, group_id, public_url)
        VALUES ($id, $publicKeyJwk, NULL, $requireDpopNonce, $groupId, $publicUrl)
      """.update.run()
    .unit
      .mapError {
        case e: PartnerEdgeLimitReached => e
        case e: Throwable => e
      }

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

  override def patchPolicies(
      id: EdgeId,
      change: EdgeRecord => Either[PolicyPatchError, EdgeRecord],
  ): Task[Option[Either[PolicyPatchError, EdgeRecord]]] =
    xa.transactMeasured("patch-edge-policies"):
      val current =
        sql"""
        SELECT e.id, e.public_key_jwk, e.old_public_key_jwk, e.require_dpop_nonce, e.overload_policy, e.access_log_policy, e.rate_limit_policies, e.policy_meta, e.group_id, g.kind, e.public_url
        FROM edges e
        JOIN edge_groups g ON g.id = e.group_id
        WHERE e.id = $id
        FOR UPDATE OF e
      """.query[EdgeRecord].run().headOption
      current.map: record =>
        change(record).map: updated =>
          sql"""
            UPDATE edges
            SET overload_policy = ${updated.overloadPolicy},
                access_log_policy = ${updated.accessLogPolicy},
                rate_limit_policies = ${updated.rateLimitPolicies},
                policy_meta = ${updated.policyMeta}
            WHERE id = $id
          """.update.run()
          updated

  override def setPublicKey(id: EdgeId, publicKeyJwk: Json.Obj): Task[Boolean] =
    xa.connectMeasured("set-edge-public-key"):
      sql"""
        UPDATE edges
        SET public_key_jwk = $publicKeyJwk
        WHERE id = $id AND public_key_jwk IS NULL
      """.update.run()
    .map(_ > 0)

  override def replacePublicKey(id: EdgeId, publicKeyJwk: Json.Obj): Task[Boolean] =
    xa.connectMeasured("replace-edge-public-key"):
      sql"""
        UPDATE edges
        SET old_public_key_jwk = public_key_jwk,
            public_key_jwk = $publicKeyJwk
        WHERE id = $id
      """.update.run()
    .map(_ > 0)

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
