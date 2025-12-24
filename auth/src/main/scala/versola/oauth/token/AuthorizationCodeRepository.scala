package versola.oauth.token

import versola.oauth.model.{AuthorizationCode, AuthorizationCodeRecord}
import versola.util.{MAC, Secret}
import zio.{Duration, Task}

trait AuthorizationCodeRepository:

  def find(code: MAC.Of[AuthorizationCode]): Task[Option[AuthorizationCodeRecord]]

  def create(
      code: MAC.Of[AuthorizationCode],
      record: AuthorizationCodeRecord,
      ttl: Duration,
  ): Task[Unit]

  def delete(code: MAC.Of[AuthorizationCode]): Task[Unit]
