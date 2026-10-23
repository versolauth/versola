package versola.oauth.account

import versola.auth.model.Password
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.{CheckPassword, PasswordReuseError}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.user.model.UserId
import zio.{IO, ZIO, ZLayer}

import scala.util.Try

/** Why a password change was refused. `code` is the stable key the Security page translates
  * (see `auth-settings.i18n.json`), so it is part of the page's contract with this API. */
enum ChangePasswordError(val code: String):
  case IncorrectCurrentPassword extends ChangePasswordError("current_password_incorrect")
  case NewPasswordNotAllowed extends ChangePasswordError("password_not_allowed")
  case PasswordReused extends ChangePasswordError("password_reused")

  /** `retryAfterSeconds` is absent for a persistent ban, which does not resolve on its own. */
  case TooManyAttempts(retryAfterSeconds: Option[Long]) extends ChangePasswordError("too_many_attempts")

trait AccountSettingsService:
  def changePassword(
      userId: UserId,
      clientId: ClientId,
      currentPassword: String,
      newPassword: String,
  ): IO[Throwable | ChangePasswordError, Unit]

object AccountSettingsService:
  def live = ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      passwordService: PasswordService,
      submissionLimiter: SubmissionLimiter,
      configuration: OAuthConfigurationService,
  ) extends AccountSettingsService:
    override def changePassword(
        userId: UserId,
        clientId: ClientId,
        currentPassword: String,
        newPassword: String,
    ): IO[Throwable | ChangePasswordError, Unit] =
      for
        status <- submissionLimiter.isBanned(clientId, userId.toString, ChallengeType.PasswordSubmit)
        _ <- status match
          case LimitStatus.Banned => ZIO.fail(ChangePasswordError.TooManyAttempts(None))
          case LimitStatus.RateLimited(after) => ZIO.fail(ChangePasswordError.TooManyAttempts(Some(after)))
          case LimitStatus.Allowed => ZIO.unit
        check <- passwordService.verifyPassword(userId, Password(currentPassword))
        _ <- check match
          case CheckPassword.Success | CheckPassword.Temporary => ZIO.unit
          case _ =>
            // Same budget as the login form's password step: this endpoint is otherwise an oracle for the current password.
            submissionLimiter.recordLimit(clientId, userId.toString, ChallengeType.PasswordSubmit) *>
              ZIO.fail(ChangePasswordError.IncorrectCurrentPassword)
        regex <- configuration.getPasswordRegex
        // An unparseable regex lets the password through, as in the login flow's own validation.
        _ <- ZIO.fail(ChangePasswordError.NewPasswordNotAllowed)
          .unless(Try(newPassword.matches(regex)).getOrElse(true))
        _ <- passwordService.setPassword(userId, Password(newPassword)).mapError:
          case _: PasswordReuseError => ChangePasswordError.PasswordReused
          case error: Throwable => error
      yield ()
