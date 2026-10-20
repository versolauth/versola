package versola.oauth.account

import org.scalamock.stubs.Stub
import versola.auth.model.Password
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.CheckPassword
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.user.model.UserId
import versola.util.UnitSpecBase
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
        service = AccountSettingsService.Impl(passwordService, submissionLimiter, configuration)
        result <- service.changePassword(userId, clientId, "OldPass1", newPassword).either
      yield assertTrue(result.left.toOption == expectedError) && verify(stubs)
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
          ),
        )*,
      ),
      serviceTestCase(
        description = "is refused with the wait while the attempt limit is in force",
        setup = (_, submissionLimiter) => submissionLimiter.isBanned.succeedsWith(LimitStatus.RateLimited(30)),
        expectedError = Some(ChangePasswordError.TooManyAttempts(Some(30))),
        verify = (passwordService, _) => assertTrue(passwordService.verifyPassword.calls.isEmpty),
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
      ),
      serviceTestCase(
        description = "lets the new password through when the configured policy is not a valid regex",
        regex = "(",
        setup = (passwordService, _) =>
          passwordService.verifyPassword.succeedsWith(CheckPassword.Success) *>
            passwordService.setPassword.succeedsWith(()),
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
          result <- AccountSettingsService.Impl(passwordService, submissionLimiter, configuration)
            .changePassword(userId, clientId, "OldPass1", "NewPass1").either
        yield assertTrue(result == Left(failure))
      },
    ),
  )
