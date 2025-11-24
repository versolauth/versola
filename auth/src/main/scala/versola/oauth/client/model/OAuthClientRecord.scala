package versola.oauth.client.model

import versola.security.Secret
import zio.prelude.{Equal, NonEmptySet}
import zio.schema.*

case class OAuthClientRecord(
    id: ClientId,
    clientName: String,
    redirectUris: NonEmptySet[String],
    scope: Set[String],
    secret: Option[Secret],
    previousSecret: Option[Secret],
) derives Schema, CanEqual, Equal:

  def isConfidential: Boolean = secret.nonEmpty

  def isPublic: Boolean = !isConfidential
