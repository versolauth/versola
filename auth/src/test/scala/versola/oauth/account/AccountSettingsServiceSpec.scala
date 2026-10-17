package versola.oauth.account

import org.scalamock.stubs.Stub
import versola.auth.model.Password
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.{CheckPassword, PasswordReuseError}
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.user.model.UserId
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

import java.util.UUID

object AccountSettingsServiceSpec extends UnitSpecBase:

  private val userId   = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val clientId = ClientId("test-client-1")

  private type Stubs = (Stub[PasswordService], Stub[SubmissionLimiter])

  private def serviceTestCase(
      description: String,
      setup: Stubs => UIO[Unit],
      expectedError: Option[Throwable | PasswordReuseError] = None,
      verify: (Stubs, Option[Throwable | PasswordReuseError]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        passwordService   <- ZIO.succeed(stub[PasswordService])
        submissionLimiter <- ZIO.succeed(stub[SubmissionLimiter])
        stubs = (passwordService, submissionLimiter)
        _ <- submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
        _ <- submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
        _ <- setup(stubs)
        service = AccountSettingsService.Impl(passwordService, submissionLimiter)
        result <- service
          .changePassword(userId, clientId, "OldPass1!", "NewPass1!")
          .either
        capturedError = result.left.toOption
        verifyResult <- verify(stubs, capturedError)
      yield assertTrue(capturedError == expectedError) && verifyResult
    }

  val spec = suite("AccountSettingsService")(
    suite("changePassword")(
      serviceTestCase(
        description = "succeeds when current password is correct",
        setup = (passwordService, _) =>
          passwordService.verifyPassword.succeedsWith(CheckPassword.Success) *>
            passwordService.setPassword.succeedsWith(()),
        expectedError = None,
        verify = (stubs, _) =>
          ZIO.succeed(assertTrue(
            stubs._1.setPassword.calls == List((userId, Password("NewPass1!"))),
          )),
      ),
      serviceTestCase(
        description = "succeeds when current password is temporary",
        setup = (passwordService, _) =>
          passwordService.verifyPassword.succeedsWith(CheckPassword.Temporary) *>
            passwordService.setPassword.succeedsWith(()),
        expectedError = None,
      ),
      serviceTestCase(
        description = "fails with BadRequest when current password is incorrect",
        setup = (passwordService, _) =>
          passwordService.verifyPassword.succeedsWith(CheckPassword.Failure),
        expectedError = Some(versola.util.http.BadRequest("current password is incorrect")),
        verify = (stubs, _) =>
          ZIO.succeed(assertTrue(
            stubs._1.setPassword.calls.isEmpty,
            stubs._2.recordLimit.calls.nonEmpty,
          )),
      ),
      serviceTestCase(
        description = "records a failed attempt when the current password is wrong",
        setup = (passwordService, _) =>
          passwordService.verifyPassword.succeedsWith(CheckPassword.Failure),
        expectedError = Some(versola.util.http.BadRequest("current password is incorrect")),
        verify = (stubs, _) =>
          ZIO.succeed(assertTrue(
            stubs._2.recordLimit.calls == List((clientId, userId.toString, ChallengeType.PasswordSubmit)),
          )),
      ),
      serviceTestCase(
        description = "fails with BadRequest when caller is banned",
        setup = (_, submissionLimiter) =>
          submissionLimiter.isBanned.succeedsWith(LimitStatus.Banned),
        expectedError = Some(versola.util.http.BadRequest("too many failed attempts")),
        verify = (stubs, _) =>
          ZIO.succeed(assertTrue(stubs._1.verifyPassword.calls.isEmpty)),
      ),
      serviceTestCase(
        description = "propagates PasswordReuseError from setPassword",
        setup = (passwordService, _) =>
          passwordService.verifyPassword.succeedsWith(CheckPassword.Success) *>
            passwordService.setPassword.failsWith(PasswordReuseError(5)),
        expectedError = Some(PasswordReuseError(5)),
      ),
    ),
  )