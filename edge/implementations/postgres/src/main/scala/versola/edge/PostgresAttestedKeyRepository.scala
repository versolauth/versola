package versola.edge

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.attestation.{AttestedKey, AttestedKeyRepository, IosEnvironment}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant

class PostgresAttestedKeyRepository(xa: TransactorZIO) extends AttestedKeyRepository, BasicCodecs:
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[IosEnvironment] = DbCodec.StringCodec.biMap(IosEnvironment.valueOf, _.toString)

  override def register(clientId: String, keyId: String, publicKey: Array[Byte], environment: IosEnvironment, at: Instant): Task[Unit] =
    xa.connectMeasured("register-attested-key"):
      // A key already recorded keeps its counter: a second attestation of it is not a way to wind it back.
      val expiresAt = at.plus(AttestedKeyRepository.Retention)
      sql"""
        INSERT INTO attested_keys (client_id, key_id, public_key, environment, counter, created_at, expires_at)
        VALUES ($clientId, $keyId, $publicKey, $environment, 0, $at, $expiresAt)
        ON CONFLICT (client_id, key_id) DO NOTHING
      """.update.run()
    .unit

  override def find(clientId: String, keyId: String): Task[Option[AttestedKey]] =
    xa.connectMeasured("find-attested-key"):
      sql"SELECT public_key, environment, counter FROM attested_keys WHERE client_id = $clientId AND key_id = $keyId"
        .query[(Array[Byte], IosEnvironment, Long)].run().headOption.map(AttestedKey.apply.tupled)

  override def advance(clientId: String, keyId: String, counter: Long, at: Instant): Task[Boolean] =
    xa.connectMeasured("advance-attested-key-counter"):
      val expiresAt = at.plus(AttestedKeyRepository.Retention)
      sql"""
        UPDATE attested_keys SET counter = $counter, expires_at = $expiresAt
        WHERE client_id = $clientId AND key_id = $keyId AND counter < $counter
        RETURNING 1
      """.query[Int].run().nonEmpty

object PostgresAttestedKeyRepository:
  val live: ZLayer[TransactorZIO, Nothing, AttestedKeyRepository] =
    ZLayer.fromFunction(PostgresAttestedKeyRepository(_))
