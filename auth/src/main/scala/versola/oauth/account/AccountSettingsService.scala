package versola.oauth.account

import versola.audit.AuditSupport.*
import versola.audit.AuthAuditDetails
import versola.audit.AuthAuditKinds
import versola.auth.model.Password
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.PasswordChangeEffect
import versola.oauth.challenge.password.model.{CheckPassword, PasswordReuseError}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.limit.{Acquisition, ChallengeType, LimitStatus, SubmissionLimiter}
import versola.oauth.logout.LogoutService
import versola.oauth.revocation.{RevocationCause, RevocationTerms}
import versola.oauth.session.model.PublicSessionId
import versola.user.model.UserId
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import zio.{IO, ZIO, ZLayer}

import scala.util.Try

/** Why a password change was refused. `code` is the stable key the Security page translates
  * (see `auth-settings.i18n.json`), so it is part of the page's contract with this API. */
enum ChangePasswordError(val code: String):
  case IncorrectCurrentPassword extends ChangePasswordError("current_password_incorrect")
  case NewPasswordNotAllowed extends ChangePasswordError("password_not_allowed")
  case PasswordReused extends ChangePasswordError("password_reused")

  /** The user asked to be signed out elsewhere but the request does not say which session is theirs, so
    * "elsewhere" cannot be told from "here". Refused rather than guessed: ending every session would sign the
    * person out of the one they are using. */
  case CurrentSessionUnknown extends ChangePasswordError("current_session_unknown")

  /** `retryAfterSeconds` is absent for a persistent ban, which does not resolve on its own. */
  case TooManyAttempts(retryAfterSeconds: Option[Long]) extends ChangePasswordError("too_many_attempts")

/** What the user asked to happen to their other sessions along with the change. */
enum OtherSessions:
  /** Leave them be. */
  case Keep

  /** End every session but `current`, the one the request came from. `current` is absent when the request
    * did not carry it (see [[ChangePasswordError.CurrentSessionUnknown]]). */
  case End(current: Option[PublicSessionId])

trait AccountSettingsService:
  def changePassword(
      userId: UserId,
      clientId: ClientId,
      currentPassword: String,
      newPassword: String,
      otherSessions: OtherSessions,
  ): IO[Throwable | ChangePasswordError, Unit]

object AccountSettingsService:
  def live = ZLayer.fromFunction(Impl(_, _, _, _, _))

  class Impl(
      passwordService: PasswordService,
      submissionLimiter: SubmissionLimiter,
      configuration: OAuthConfigurationService,
      audit: AuditEvents,
      logout: LogoutService,
  ) extends AccountSettingsService:

    /** `account.password_changed`: a change that went through, and the two refusals that are about the person and not the form, a
      * wrong current password and a budget that is used up. The user is the actor and the subject. */
    private def auditPasswordChange(userId: UserId, outcome: AuditOutcome, reason: Option[String]): IO[Throwable, Unit] =
      audit.log(
        AuditDraft(
          kind = AuthAuditKinds.AccountPasswordChanged,
          outcome = outcome,
          reason = reason,
          actor = None,
          userId = Some(userId),
          target = None,
          details = AuthAuditDetails.Account(via = "account_settings", detail = None),
        ),
      )

    override def changePassword(
        userId: UserId,
        clientId: ClientId,
        currentPassword: String,
        newPassword: String,
        otherSessions: OtherSessions,
    ): IO[Throwable | ChangePasswordError, Unit] =
      changePasswordUnaudited(userId, clientId, currentPassword, newPassword, otherSessions).foldZIO(
        {
          case error: ChangePasswordError.TooManyAttempts => auditPasswordChange(userId, AuditOutcome.denied, Some(error.code)) *> ZIO.fail(error)
          case ChangePasswordError.IncorrectCurrentPassword =>
            auditPasswordChange(userId, AuditOutcome.failure, Some(ChangePasswordError.IncorrectCurrentPassword.code)) *>
              ZIO.fail(ChangePasswordError.IncorrectCurrentPassword)
          case error => ZIO.fail(error)
        },
        _ => auditPasswordChange(userId, AuditOutcome.success, None),
      )
    private def changePasswordUnaudited(
        userId: UserId,
        clientId: ClientId,
        currentPassword: String,
        newPassword: String,
        otherSessions: OtherSessions,
    ): IO[Throwable | ChangePasswordError, Unit] =
      for
        // Same budget as the login form's password step (this endpoint is otherwise an oracle for the current password), charged the same
        // way: the attempt is claimed before the password is looked at (see [[SubmissionLimiter.acquireAll]]), so parallel guesses are cut
        // off after the configured number instead of every one of them passing a read of the status.
        acquired <- submissionLimiter.acquireAll(clientId, List(userId.toString), ChallengeType.PasswordSubmit)
        charges <- acquired match
          case Acquisition.Denied(LimitStatus.RateLimited(after)) => ZIO.fail(ChangePasswordError.TooManyAttempts(Some(after)))
          case Acquisition.Denied(_) => ZIO.fail(ChangePasswordError.TooManyAttempts(None))
          case Acquisition.Granted(_, charges) => ZIO.succeed(charges)
        check <- passwordService.verifyPassword(userId, Password(currentPassword))
        _ <- check match
          // A right password spends none of the budget.
          case CheckPassword.Success | CheckPassword.Temporary => submissionLimiter.refund(charges)
          case _ => ZIO.fail(ChangePasswordError.IncorrectCurrentPassword)
        regex <- configuration.getPasswordRegex
        // An unparseable regex lets the password through, as in the login flow's own validation.
        _ <- ZIO.fail(ChangePasswordError.NewPasswordNotAllowed)
          .unless(Try(newPassword.matches(regex)).getOrElse(true))
        // Decided before the password is stored, so a request that cannot say which session is the caller's
        // changes nothing; the same transaction that stores the password ends the other sessions.
        change <- otherSessions match
          case OtherSessions.Keep => ZIO.succeed(PasswordChangeEffect.Untouched)
          case OtherSessions.End(None) => ZIO.fail(ChangePasswordError.CurrentSessionUnknown)
          case OtherSessions.End(Some(current)) =>
            configuration.accessTokenTtls.map: ttls =>
              PasswordChangeEffect.EndOtherSessions(current, RevocationTerms.widest(RevocationCause.PasswordChanged, ttls))
        ended <- passwordService.setPassword(userId, Password(newPassword), change).mapError:
          case _: PasswordReuseError => ChangePasswordError.PasswordReused
          case error: Throwable => error
        // The password is changed and the sessions are ended and recorded: telling their relying parties is
        // best effort, queued, and no failure to do so undoes any of it.
        _ <- logout.announceEnded(ended, RevocationCause.PasswordChanged)
      yield ()
