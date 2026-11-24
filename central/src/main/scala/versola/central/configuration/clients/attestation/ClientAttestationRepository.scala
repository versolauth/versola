package versola.central.configuration.clients.attestation

import versola.central.configuration.clients.ClientId
import versola.util.CacheSource
import zio.Task

import java.time.Instant

/** Where a client's attestation policy is kept. A client with no row has no policy, which reads as `off`. */
trait ClientAttestationRepository extends CacheSource[Map[ClientId, ClientAttestation]]:

  def find(clientId: ClientId): Task[Option[ClientAttestation]]

  /** Every stored policy, for the cache the sync an edge makes is answered from: one read for all the clients, not one per client. */
  def all: Task[Map[ClientId, ClientAttestation]]

  override def getAll: Task[Map[ClientId, ClientAttestation]] = all

  /** Replaces the client's policy. The client must exist. */
  def put(clientId: ClientId, attestation: ClientAttestation, at: Instant): Task[Unit]

  /** Removes the client's policy; whether there was one. */
  def delete(clientId: ClientId): Task[Boolean]
