package versola.oauth.token

import versola.oauth.model.{AuthorizationCode, AuthorizationCodeRecord}
import versola.oauth.session.model.RefreshTokenFamilyId
import versola.util.MAC
import zio.{Duration, Task}

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
   * @return Left(familyId) if the code was already used -- the rotation family its first
   *         exchange started, which is what a replay revokes
   *         Right(()) if this is the first use
   */
  def markAsUsed(code: MAC.Of[AuthorizationCode]): Task[Either[RefreshTokenFamilyId, Unit]]