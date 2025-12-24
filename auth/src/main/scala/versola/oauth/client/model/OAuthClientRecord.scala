package versola.oauth.client.model

import versola.util.Secret
import zio.Duration
import zio.prelude.{Equal, NonEmptySet}
import zio.schema.*

case class OAuthClientRecord(
    id: ClientId,
    clientName: String,
    redirectUris: NonEmptySet[String],
    scope: Set[String],
    secret: Option[Secret],
    previousSecret: Option[Secret],
    accessTokenTtl: Duration,
    accessTokenType: AccessTokenType
) derives Schema, CanEqual, Equal:

  def isConfidential: Boolean = secret.nonEmpty

  def isPublic: Boolean = !isConfidential
