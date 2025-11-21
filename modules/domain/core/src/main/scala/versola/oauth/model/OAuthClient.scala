package versola.oauth.model

import versola.util.{Argon2Hash, Argon2Salt}
import zio.prelude.NonEmptySet
import zio.schema.*

case class OAuthClient(
    id: ClientId,
    clientName: String,
    redirectUris: NonEmptySet[String],
    scope: Set[String],
    secretHash: Option[Argon2Hash],
    secretSalt: Option[Argon2Salt],
    previousSecretHash: Option[Argon2Hash],
    previousSecretSalt: Option[Argon2Salt],
) derives Schema:
  override def equals(obj: Any) = obj match
    case that: OAuthClient =>
      this.id == that.id &&
      this.clientName == that.clientName &&
      this.redirectUris == that.redirectUris &&
      this.scope == that.scope &&
      this.secretHash.map(_.toBase64Url) == that.secretHash.map(_.toBase64Url) &&
      this.secretSalt.map(_.toBase64Url) == that.secretSalt.map(_.toBase64Url) &&
      this.previousSecretHash.map(_.toBase64Url) == that.previousSecretHash.map(_.toBase64Url) &&
      this.previousSecretSalt.map(_.toBase64Url) == that.previousSecretSalt.map(_.toBase64Url)

    case _ => false

  def isConfidential: Boolean = secretHash.nonEmpty

  def isPublic: Boolean = !isConfidential

  def hasPreviousSecret: Boolean = previousSecretHash.nonEmpty && previousSecretSalt.nonEmpty
