package versola.oauth.model

import versola.security.Secret
import zio.prelude.NonEmptySet
import zio.schema.*

case class OAuthClient(
    id: ClientId,
    clientName: String,
    redirectUris: NonEmptySet[String],
    scope: Set[String],
    secret: Option[Secret],
    previousSecret: Option[Secret],
) derives Schema, CanEqual:

  def isConfidential: Boolean = secret.nonEmpty

  def isPublic: Boolean = !isConfidential
