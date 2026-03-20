package versola.oauth.token

import versola.oauth.model.{AccessToken, AuthorizationCode, AuthorizationCodeRecord, RefreshToken}
import versola.oauth.session.model.SessionId
import versola.util.{MAC, Secret}
import zio.{Duration, IO, Task}

trait AuthorizationCodeRepository:

  def find(code: MAC.Of[AuthorizationCode]): Task[Option[AuthorizationCodeRecord]]

  def create(
      code: MAC.Of[AuthorizationCode],
      record: AuthorizationCodeRecord,
      ttl: Duration,
  ): Task[Unit]

  def delete(code: MAC.Of[AuthorizationCode]): Task[Unit]

  /**
   * Mark an authorization code as used.
   *
   * @param code The authorization code MAC
   * @return Left(accessToken) if the code was already used (returns the stored access token for revocation),
   *         Right(()) if this is the first use
   */
  def markAsUsed(code: MAC.Of[AuthorizationCode]): Task[Either[AccessToken, Unit]]