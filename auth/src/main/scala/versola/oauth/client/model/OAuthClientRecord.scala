package versola.oauth.client.model

import versola.util.Secret
import zio.Duration
import zio.prelude.{Equal, NonEmptySet}
import zio.schema.*

case class OAuthClientRecord(
    id: ClientId,
    clientName: String,
    redirectUris: NonEmptySet[String],
    scope: Set[ScopeToken],
    externalAudience: List[ClientId],
    secret: Option[Secret],
    previousSecret: Option[Secret],
    accessTokenTtl: Duration,
) derives Schema, CanEqual, Equal:

  def audience: List[ClientId] = id :: externalAudience

  def isConfidential: Boolean = secret.nonEmpty

  def isPublic: Boolean = !isConfidential
