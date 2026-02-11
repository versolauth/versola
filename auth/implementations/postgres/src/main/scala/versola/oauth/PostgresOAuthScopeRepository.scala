package versola.oauth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.OAuthScopeRepository
import versola.oauth.client.model.{Claim, Scope, ScopeDescription, ScopeRecord, ScopeToken}
import versola.oauth.model.*
import versola.util.postgres.BasicCodecs
import zio.IO

class PostgresOAuthScopeRepository(
    xa: TransactorZIO,
) extends OAuthScopeRepository, BasicCodecs:
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[Claim] = DbCodec.StringCodec.biMap(Claim(_), identity[String])
  given DbCodec[ScopeDescription] = DbCodec.StringCodec.biMap(ScopeDescription(_), identity[String])
  given DbCodec[ScopeRecord] = DbCodec.derived[ScopeRecord]

  override def getAll: IO[Throwable, Map[ScopeToken, Scope]] =
    xa.connect:
      sql"""
        SELECT name, description, claims FROM oauth_scopes
      """.query[ScopeRecord].run().map: record =>
        record.name -> Scope(
          claims = record.claims,
          description = record.description,
        )
      .toMap

  override def createOrUpdate(scopes: Vector[ScopeRecord]): IO[Throwable, Unit] =
    xa.connect:
      batchUpdate(scopes): scope =>
        sql"""
          INSERT INTO oauth_scopes (name, description, claims)
          VALUES (${scope.name}, ${scope.description}, ${scope.claims})
          ON CONFLICT (name) DO UPDATE SET
           description = ${scope.description},
           claims = ${scope.claims}
        """.update
    .unit

  override def delete(names: Vector[ScopeToken]): IO[Throwable, Unit] =
    xa.connect:
      batchUpdate(names): scopeName =>
        sql"""
          DELETE FROM oauth_scopes
          WHERE name = $scopeName
        """.update
    .unit
