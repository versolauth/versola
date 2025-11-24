package versola.oauth.client

import versola.oauth.client.model.{ClientId, ClientSecret, ExternalOAuthClient, OauthProviderName}
import zio.Task

trait ExternalOAuthClientRepository:

  /** Register a new external OAuth client */
  def register(
      provider: OauthProviderName,
      clientId: ClientId,
      clientSecret: ClientSecret,
  ): Task[Unit]

  /** List all external OAuth clients */
  def listAll(): Task[Vector[ExternalOAuthClient]]

  /** Rotate client secret - moves current secret to old_password and sets new secret */
  def rotateSecret(
      provider: OauthProviderName,
      clientId: ClientId,
      newClientSecret: ClientSecret,
  ): Task[Unit]

  /** Delete old secret for a client */
  def deleteOldSecret(
      provider: OauthProviderName,
      clientId: ClientId,
  ): Task[Unit]
