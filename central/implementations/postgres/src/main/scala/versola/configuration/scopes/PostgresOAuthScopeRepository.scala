package versola.configuration.scopes

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.json.JsonBDbCodec
import versola.central.configuration.scopes.{Claim, ClaimRecord, OAuthScopeRepository, ScopeRecord, ScopeToken}
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateClaim, PatchScope}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}
import zio.json.ast.Json
import zio.json.{EncoderOps, JsonDecoder, JsonEncoder}

class PostgresOAuthScopeRepository(
    xa: TransactorZIO,
) extends OAuthScopeRepository, BasicCodecs:
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[Claim] = DbCodec.StringCodec.biMap(Claim(_), identity[String])
  given JsonBDbCodec[ClaimRecord] = dbCodecFromJsonCodec
  given DbCodec[ScopeRecord] = DbCodec.derived

  override def getAll: Task[Vector[ScopeRecord]] =
    xa.connect:
      sql"""
            SELECT tenant_id, id, description, claims FROM oauth_scopes
         """.query[ScopeRecord].run()

  override def findScope(
      tenantId: TenantId,
      scopeId: ScopeToken,
  ): Task[Option[ScopeRecord]] =
    xa.connect:
      sql"""
        SELECT tenant_id, id, description, claims FROM oauth_scopes
        WHERE tenant_id = $tenantId AND id = $scopeId
      """.query[ScopeRecord].run().headOption

  override def createScope(
      tenantId: TenantId,
      id: ScopeToken,
      description: Map[String, String],
      claims: List[CreateClaim],
  ): Task[Unit] =
    xa.connect:
      sql"""
            INSERT INTO oauth_scopes (id, tenant_id, description, claims)
            VALUES ($id, $tenantId, $description, ${claims.map(_.asRecord)}::jsonb[])
         """.update.run()
    .unit

  override def updateScope(
      tenantId: TenantId,
      id: ScopeToken,
      patch: PatchScope,
  ): Task[Unit] =
    xa.repeatableRead.transact:
      val scope =
        sql"""SELECT tenant_id, id, description, claims::jsonb[] FROM oauth_scopes WHERE tenant_id = $tenantId AND id = $id"""
          .query[ScopeRecord].run().head

      val newClaims = scope.claims
        .filterNot(c => patch.delete.contains(c.id))
        .appendedAll(patch.add.map(_.asRecord))
        .map { record =>
          patch.update.find(_.id == record.id) match
            case Some(update) =>
              record.copy(description = update.description.patch(record.description))
            case None =>
              record
        }

      val newDesc = patch.description.patch(scope.description)

      sql"""
             UPDATE oauth_scopes
               SET description = $newDesc,
                   claims = $newClaims::jsonb[]
               WHERE tenant_id = $tenantId and id = $id
         """.update.run()
    .unit

  override def deleteScope(
      tenantId: TenantId,
      id: ScopeToken,
  ): Task[Unit] =
    xa.connect:
      sql"""DELETE FROM oauth_scopes WHERE tenant_id = $tenantId AND id = $id""".update.run()
    .unit

object PostgresOAuthScopeRepository:
  def live: ZLayer[TransactorZIO, Nothing, OAuthScopeRepository] =
    ZLayer.fromFunction(PostgresOAuthScopeRepository(_))
