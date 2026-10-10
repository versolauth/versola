package versola.oauth.account

import versola.auth.model.Password
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.{CheckPassword, PasswordReuseError}
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.user.model.UserId
import zio.{IO, ZIO, ZLayer}

trait AccountSettingsService:
  def changePassword(
      userId: UserId,
      clientId: ClientId,
      currentPassword: String,
      newPassword: String,
  ): IO[Throwable | PasswordReuseError, Unit]

object AccountSettingsService:
  def live = ZLayer.fromFunction(Impl(_, _))

  class Impl(
      passwordService: PasswordService,
      submissionLimiter: SubmissionLimiter,
  ) extends AccountSettingsService:
    override def changePassword(
        userId: UserId,
        clientId: ClientId,
        currentPassword: String,
        newPassword: String,
    ): IO[Throwable | PasswordReuseError, Unit] =
      for
        ban <- submissionLimiter.isBanned(clientId, userId.toString, ChallengeType.PasswordSubmit)
        _ <- ban match
          case LimitStatus.Banned             => ZIO.fail(versola.util.http.BadRequest("too many failed attempts"))
          case LimitStatus.RateLimited(after) => ZIO.fail(versola.util.http.BadRequest(s"too many failed attempts, retry after $after seconds"))
          case LimitStatus.Allowed            => ZIO.unit
        check <- passwordService.verifyPassword(userId, Password(currentPassword))
        _ <- check match
          case CheckPassword.Success   => ZIO.unit
          case CheckPassword.Temporary => ZIO.unit
          case _ =>
            submissionLimiter.recordLimit(clientId, userId.toString, ChallengeType.PasswordSubmit) *>
              ZIO.fail(versola.util.http.BadRequest("current password is incorrect"))
        _ <- passwordService.setPassword(userId, Password(newPassword))
          .mapError:
            case e: PasswordReuseError => e
            case t: Throwable          => t
      yield ()