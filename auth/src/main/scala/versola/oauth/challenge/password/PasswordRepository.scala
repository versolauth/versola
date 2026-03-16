package versola.oauth.challenge.password

import versola.auth.model.{Password, PasswordRecord}
import versola.oauth.challenge.password.model.PasswordReuseError
import versola.user.model.UserId
import versola.util.{Salt, Secret}
import zio.{IO, Task}

trait PasswordRepository:
  def list(userId: UserId): Task[Vector[PasswordRecord]]

  def create(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      historySize: Int,
      numDifferent: Int,
  ): IO[Throwable | PasswordReuseError, Unit]
