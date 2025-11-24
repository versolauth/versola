package versola.oauth.token

import versola.oauth.model.{AuthorizationCode, AuthorizationCodeRecord}
import versola.security.{MAC, Secret}
import zio.{Duration, Task}

trait AuthorizationCodeRepository:

  def find(code: MAC): Task[Option[AuthorizationCodeRecord]]

  def create(
      code: MAC,
      record: AuthorizationCodeRecord,
      ttl: Duration,
  ): Task[Unit]

  def delete(code: MAC): Task[Unit]
