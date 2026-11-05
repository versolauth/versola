package versola.oauth.account

import org.scalamock.stubs.Stub
import versola.audit.{AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents}
import versola.auth.model.Password
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.{CheckPassword, PasswordReuseError}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.user.model.UserId
import versola.util.UnitSpecBase
import versola.util.audit.{AuditDraft, AuditOutcome}
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object AccountSettingsServiceSpec extends UnitSpecBase:

  private val userId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val clientId = ClientId("test-client-1")
  private val passwordRegex = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).{8,}$"

  private type Stubs = (Stub[PasswordService], Stub[SubmissionLimiter])
  private type Failure = Throwable | ChangePasswordError

  private def serviceTestCase(
      description: String,
      setup: Stubs => UIO[Unit],
      newPassword: String = "NewPass1",
      regex: String = passwordRegex,
      expectedError: Option[Failure] = None,
      verify: Stubs => TestResult = _ => assertCompletes,
      verifyAudit: List[AuditDraft] => TestResult = _ => assertCompletes,
  ) =
    test(description) {
      for
        passwordService <- ZIO.succeed(stub[PasswordService])
        submissionLimiter <- ZIO.succeed(stub[SubmissionLimiter])
        configuration <- ZIO.succeed(stub[OAuthConfigurationService])
        stubs = (passwordService, submissionLimiter)
        _ <- submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
        _ <- submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
        _ <- configuration.getPasswordRegex.succeedsWith(regex)
        _ <- setup(stubs)
        audit = CapturingAuditEvents.shared()
        service = AccountSettingsService.Impl(passwordService, submissionLimiter, configuration, audit)
        result <- service.changePassword(userId, clientId, "OldPass1", newPassword).either
        drafts <- audit.recorded
      yield assertTrue(result.left.toOption == expectedError) && verify(stubs) && verifyAudit(drafts)
    }

  val spec = suite("AccountSettingsService")(
    suite("changePassword")(
      serviceTestCase(
        description = "sets the new password when the current one is correct",
        setup = (passwordService, _) =>
          passwordService.verifyPassword.succeedsWith(CheckPassword.Success) *>
            passwordService.setPassword.succeedsWith(()),
        verify = (passwordService, _) =>
          assertTrue(
            passwordService.verifyPassword.calls == List((userId, Password("OldPass1"))),
            passwordService.setPassword.calls == List((userId, Password("NewPass1"))),
          ),
        // account.password_changed: the user is the subject (and, with no actor given, the actor), and neither password is in it
        verifyAudit = drafts =>
          assertTrue(
            drafts.map(_.kind) == List(AuthAuditKinds.AccountPasswordChanged),
            drafts.head.outcome == AuditOutcome.success,
            drafts.head.userId == Some(userId.asInstanceOf[java.util.UUID]),
            drafts.head.details == AuthAuditDetails.Account(via = "account_settings"),
            !drafts.head.details.toJson.toString.contains("OldPass1"),
            !drafts.head.details.toJson.toString.contains("NewPass1"),
          ),
      ),
      serviceTestCase(
        description = "accepts a temporary password as the current one",
        setup = (passwordService, _) =>
          passwordService.verifyPassword.succeedsWith(CheckPassword.Temporary) *>
            passwordService.setPassword.succeedsWith(()),
      ),
      suite("a wrong current password")(
        List(
          ("is wrong", CheckPassword.Failure),
          ("is one of the retired ones", CheckPassword.OldPassword(Instant.EPOCH)),
          ("is an expired temporary one", CheckPassword.TemporaryExpired),
        ).map((name, check) =>
          serviceTestCase(
            description = s"is refused and charged to the attempt budget when it $name",
            setup = (passwordService, _) => passwordService.verifyPassword.succeedsWith(check),
            expectedError = Some(ChangePasswordError.IncorrectCurrentPassword),
            verify = (passwordService, submissionLimiter) =>
              assertTrue(
                passwordService.setPassword.calls.isEmpty,
                submissionLimiter.recordLimit.calls == List((clientId, userId.toString, ChallengeType.PasswordSubmit)),
              ),
            // a wrong current password on a change is what a person who has taken over a session would produce: it is on record
            verifyAudit = drafts =>
              assertTrue(
                drafts.map(_.kind) == List(AuthAuditKinds.AccountPasswordChanged),
                drafts.head.outcome == AuditOutcome.failure,
                drafts.head.reason == Some("current_password_incorrect"),
              ),
          ),
        )*,
      ),
      serviceTestCase(
        description = "is refused with the wait while the attempt limit is in force",
        setup = (_, submissionLimiter) => submissionLimiter.isBanned.succeedsWith(LimitStatus.RateLimited(30)),
        expectedError = Some(ChangePasswordError.TooManyAttempts(Some(30))),
        verify = (passwordService, _) => assertTrue(passwordService.verifyPassword.calls.isEmpty),
        verifyAudit = drafts => assertTrue(drafts.map(d => (d.outcome, d.reason)) == List((AuditOutcome.denied, Some("too_many_attempts")))),
      ),
      serviceTestCase(
        description = "is refused without a wait when the user is banned",
        setup = (_, submissionLimiter) => submissionLimiter.isBanned.succeedsWith(LimitStatus.Banned),
        expectedError = Some(ChangePasswordError.TooManyAttempts(None)),
        verify = (passwordService, _) => assertTrue(passwordService.verifyPassword.calls.isEmpty),
      ),
      serviceTestCase(
        description = "refuses a new password the tenant's policy does not allow, without touching the stored one",
        newPassword = "short",
        setup = (passwordService, _) => passwordService.verifyPassword.succeedsWith(CheckPassword.Success),
        expectedError = Some(ChangePasswordError.NewPasswordNotAllowed),
        verify = (passwordService, submissionLimiter) =>
          assertTrue(passwordService.setPassword.calls.isEmpty, submissionLimiter.recordLimit.calls.isEmpty),
        // a form validation, not an event about the account
        verifyAudit = drafts => assertTrue(drafts.isEmpty),
      ),
      serviceTestCase(
        description = "lets the new password through when the configured policy is not a valid regex",
        regex = "(",
        setup = (passwordService, _) =>
          passwordService.verifyPassword.succeedsWith(CheckPassword.Success) *>
            passwordService.setPassword.succeedsWith(()),
      ),
      serviceTestCase(
        description = "reports a reused password as such",
        setup = (passwordService, _) =>
          passwordService.verifyPassword.succeedsWith(CheckPassword.Success) *>
            passwordService.setPassword.failsWith(PasswordReuseError(5)),
        expectedError = Some(ChangePasswordError.PasswordReused),
        verifyAudit = drafts => assertTrue(drafts.isEmpty),
      ),
      test("passes an infrastructure failure through as it is") {
        val failure = IllegalStateException("db down")
        for
          passwordService <- ZIO.succeed(stub[PasswordService])
          submissionLimiter <- ZIO.succeed(stub[SubmissionLimiter])
          configuration <- ZIO.succeed(stub[OAuthConfigurationService])
          _ <- submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- configuration.getPasswordRegex.succeedsWith(passwordRegex)
          _ <- passwordService.verifyPassword.succeedsWith(CheckPassword.Success)
          _ <- passwordService.setPassword.failsWith(failure)
          result <- AccountSettingsService.Impl(passwordService, submissionLimiter, configuration, CapturingAuditEvents.shared())
            .changePassword(userId, clientId, "OldPass1", "NewPass1").either
        yield assertTrue(result == Left(failure))
      },
    ),
  )
