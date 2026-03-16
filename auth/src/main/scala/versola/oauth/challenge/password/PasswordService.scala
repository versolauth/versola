package versola.oauth.challenge.password

import versola.auth.model.{Password, PasswordRecord}
import versola.oauth.challenge.password.model.{CheckPassword, PasswordReuseError}
import versola.user.model.UserId
import versola.util.{CoreConfig, MAC, Salt, Secret, SecureRandom, SecurityService}
import zio.prelude.EqualOps
import zio.{IO, Task, UIO, ZIO, ZLayer}

import java.time.Instant

trait PasswordService:
  def verifyPassword(userId: UserId, password: Password): Task[CheckPassword]

  def setPassword(userId: UserId, password: Password): IO[Throwable | PasswordReuseError, Unit]

object PasswordService:
  def live = ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      passwordRepository: PasswordRepository,
      securityService: SecurityService,
      secureRandom: SecureRandom,
      config: CoreConfig,
  ) extends PasswordService:
    import config.security.passwords.{historySize, pepper, numDifferent}

    override def verifyPassword(userId: UserId, password: Password): Task[CheckPassword] =
      for
        allPasswords <- passwordRepository.list(userId)
        result <- allPasswords match
          case Vector() =>
            ZIO.succeed(CheckPassword.Failure)

          case Vector(current, historical*) =>
            check(password, current).flatMap:
              case true =>
                ZIO.succeed(CheckPassword.Success)
              case false =>
                ZIO.foldLeft(historical)(Option.empty[CheckPassword]) {
                  case (None, record) =>
                    check(password, record)
                      .map(Option.when(_)(CheckPassword.OldPassword(record.createdAt)))
                  case (Some(res), _) => ZIO.none
                }.someOrElse(CheckPassword.Failure)
      yield result

    private def check(password: Password, record: PasswordRecord): Task[Boolean] =
      securityService.hashPassword(Secret.fromString(password), record.salt, pepper)
        .map(mac => mac === MAC(record.password))

    override def setPassword(userId: UserId, password: Password): IO[Throwable | PasswordReuseError, Unit] =
      for
        salt <- secureRandom.nextBytes(16).map(Salt(_))
        hash <- securityService.hashPassword(Secret.fromString(password), salt, pepper)
        record <- passwordRepository.create(userId, Secret(hash), salt, historySize, numDifferent)
      yield ()
