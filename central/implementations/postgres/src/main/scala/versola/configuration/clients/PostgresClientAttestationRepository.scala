package versola.configuration.clients

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.ClientId
import versola.central.configuration.clients.attestation.{
  AndroidApp,
  AttestationMode,
  ClientAttestation,
  ClientAttestationRepository,
  IosAttestation,
}
import versola.util.postgres.BasicCodecs
import zio.json.JsonCodec
import zio.{Task, ZLayer}

import java.time.Instant

class PostgresClientAttestationRepository(xa: TransactorZIO) extends ClientAttestationRepository, BasicCodecs:
  import PostgresClientAttestationRepository.Identities

  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[AttestationMode] = DbCodec.StringCodec.biMap(AttestationMode.valueOf, _.toString)
  given DbCodec[Identities] = jsonBCodec[Identities]

  private case class Row(clientId: ClientId, mode: AttestationMode, identities: Identities) derives DbCodec:
    def attestation: ClientAttestation =
      ClientAttestation(mode = mode, ios = identities.ios, android = identities.android)

  override def find(clientId: ClientId): Task[Option[ClientAttestation]] =
    xa.connectMeasured("find-client-attestation"):
      sql"""
        SELECT client_id, mode, identities FROM client_attestation_policies WHERE client_id = $clientId
      """.query[Row].run().headOption.map(_.attestation)

  override def all: Task[Map[ClientId, ClientAttestation]] =
    xa.connectMeasured("all-client-attestations"):
      sql"SELECT client_id, mode, identities FROM client_attestation_policies".query[Row].run()
        .map(row => row.clientId -> row.attestation).toMap

  override def put(clientId: ClientId, attestation: ClientAttestation, at: Instant): Task[Unit] =
    xa.connectMeasured("put-client-attestation"):
      val mode = attestation.mode
      val identities = Identities(ios = attestation.ios, android = attestation.android)
      sql"""
        INSERT INTO client_attestation_policies (client_id, mode, identities, updated_at)
        VALUES ($clientId, $mode, $identities, $at)
        ON CONFLICT (client_id) DO UPDATE SET mode = EXCLUDED.mode, identities = EXCLUDED.identities, updated_at = EXCLUDED.updated_at
      """.update.run()
    .unit

  override def delete(clientId: ClientId): Task[Boolean] =
    xa.connectMeasured("delete-client-attestation"):
      sql"DELETE FROM client_attestation_policies WHERE client_id = $clientId".update.run() > 0

object PostgresClientAttestationRepository:

  /** What the `identities` column holds: the builds the policy accepts, apart from the mode, which has a column. */
  case class Identities(
      ios: Option[IosAttestation],
      android: List[AndroidApp],
  ) derives JsonCodec

  def live: ZLayer[TransactorZIO, Nothing, ClientAttestationRepository] =
    ZLayer.fromFunction(PostgresClientAttestationRepository(_))
