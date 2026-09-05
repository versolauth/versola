package versola.oauth.consent

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec}
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.consent.model.ConsentRecord
import versola.user.model.UserId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresConsentRepository(xa: TransactorZIO) extends ConsentRepository, BasicCodecs:
  import PgCodec.SeqCodec
  import SqlArrayCodec.StringSqlArrayCodec

  private given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  private given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  private given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  private given DbCodec[Instant] = DbCodec.InstantCodec
  private given DbCodec[ConsentRecord] = DbCodec.derived[ConsentRecord]

  override def find(userId: UserId, clientId: ClientId): Task[Option[ConsentRecord]] =
    xa.connectMeasured("find-user-consent"):
      sql"""SELECT user_id, client_id, scope, granted_at, expires_at
            FROM user_consents
            WHERE user_id = $userId AND client_id = $clientId"""
        .query[ConsentRecord]
        .run()
        .headOption

  override def upsert(record: ConsentRecord): Task[Unit] =
    xa.connectMeasured("upsert-user-consent"):
      sql"""INSERT INTO user_consents (user_id, client_id, scope, granted_at, expires_at)
            VALUES (
              ${record.userId},
              ${record.clientId},
              ${record.scope},
              ${record.grantedAt},
              ${record.expiresAt}
            )
            ON CONFLICT (user_id, client_id) DO UPDATE SET
              scope = ${record.scope},
              granted_at = ${record.grantedAt},
              expires_at = ${record.expiresAt}"""
        .update.run()
    .unit

  override def delete(userId: UserId, clientId: ClientId): Task[Unit] =
    xa.connectMeasured("delete-user-consent"):
      sql"""DELETE FROM user_consents WHERE user_id = $userId AND client_id = $clientId"""
        .update.run()
    .unit

object PostgresConsentRepository:
  def live: ZLayer[TransactorZIO, Throwable, ConsentRepository] =
    ZLayer.fromFunction(PostgresConsentRepository(_))
