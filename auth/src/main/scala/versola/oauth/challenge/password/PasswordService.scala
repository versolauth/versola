package versola.oauth.challenge.password

import versola.auth.model.{Password, PasswordRecord}
import versola.oauth.challenge.password.model.{CheckPassword, PasswordReuseError}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.user.model.UserId
import versola.util.{CoreConfig, MAC, Salt, Secret, SecureRandom, SecurityService}
import zio.prelude.EqualOps
import zio.{IO, Task, ZIO, ZLayer}

trait PasswordService:
  def verifyPassword(userId: UserId, password: Password): Task[CheckPassword]

  def setPassword(clientId: ClientId, userId: UserId, password: Password): IO[Throwable | PasswordReuseError, Unit]

object PasswordService:
  def live = ZLayer.fromFunction(Impl(_, _, _, _, _))

  class Impl(
      passwordRepository: PasswordRepository,
      securityService: SecurityService,
      secureRandom: SecureRandom,
      config: CoreConfig,
      configuration: OAuthConfigurationService,
  ) extends PasswordService:
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
      securityService.hashPassword(Secret.fromString(password), record.salt, config.security.passwordsSecret)
        .map(mac => mac === MAC(record.password))

    override def setPassword(clientId: ClientId, userId: UserId, password: Password): IO[Throwable | PasswordReuseError, Unit] =
      for
        settings <- configuration.getPasswordHistorySettings(clientId)
        salt <- secureRandom.nextBytes(16).map(Salt(_))
        hash <- securityService.hashPassword(Secret.fromString(password), salt, config.security.passwordsSecret)
        record <- passwordRepository.create(userId, Secret(hash), salt, settings.historySize, settings.numDifferent)
      yield ()
