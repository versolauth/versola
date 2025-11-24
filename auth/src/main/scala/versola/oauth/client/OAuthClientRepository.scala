package versola.oauth.client

import versola.oauth.client.model.{ClientId, OAuthClientRecord}
import versola.util.CacheSource
import zio.*

trait OAuthClientRepository extends CacheSource[Map[ClientId, OAuthClientRecord]]:

  /** Create a new OAuth client */
  def create(client: OAuthClientRecord): Task[Unit]

  /** Update non-secret properties of an existing OAuth client */
  def update(
      clientId: ClientId,
      clientName: String,
      redirectUris: Set[String],
      scope: Set[String],
  ): Task[Unit]

  /** Rotate secret: current active becomes previous, new secret becomes active */
  def rotateSecret(clientId: ClientId, newSecret: Array[Byte]): Task[Unit]

  /** Delete the previous secret */
  def deletePreviousSecret(clientId: ClientId): Task[Unit]

  /** Delete multiple clients by ID */
  def delete(clientIds: Vector[ClientId]): Task[Unit]
