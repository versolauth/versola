package versola.edge.attestation

import zio.Task

import java.time.Instant

/** An App Attest key that has been attested to this edge: its public key, to check its assertions with, the Apple environment it
  * was attested in (a policy that later accepts only production does not go on accepting a development key), and the counter of
  * the last assertion accepted. */
final case class AttestedKey(publicKey: Array[Byte], environment: IosEnvironment, counter: Long)

/** The one thing App Attest needs edge to remember. An Android attestation stands on its own -- its key is the DPoP key, which is
  * proved by the request itself -- but an iOS app proves a request by a key that is not the DPoP key, so that key has to be known,
  * and its counter is what stops an assertion from being used twice. Shared by every replica of the edge. */
trait AttestedKeyRepository:

  /** Records an attested key. A key that is already recorded is left as it is: a counter that has moved is not set back. */
  def register(clientId: String, keyId: String, publicKey: Array[Byte], environment: IosEnvironment, at: Instant): Task[Unit]

  def find(clientId: String, keyId: String): Task[Option[AttestedKey]]

  /** Moves the counter forward to `counter`, if it is greater than the one stored. False when it is not: the assertion has been
    * used, or one after it has. Atomic, so that two replicas given the same assertion cannot both succeed. */
  def advance(clientId: String, keyId: String, counter: Long, at: Instant): Task[Boolean]
