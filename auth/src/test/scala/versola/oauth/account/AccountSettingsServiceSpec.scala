package versola.oauth.account

import org.scalamock.stubs.Stub
import versola.audit.{AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents}
import versola.auth.model.Password
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.{CheckPassword, PasswordChangeEffect, PasswordReuseError}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.oauth.logout.LogoutService
import versola.oauth.revocation.{RevocationCause, RevocationTerms}
import versola.oauth.session.model.{ClientEntry, PublicSessionId, SessionRecord, UserAgentId}
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
  private val currentSession = PublicSessionId("current-session")
  private val ttls = Map(clientId -> 5.minutes, ClientId("slow-client") -> 30.minutes)
  private val terms = RevocationTerms(cause = RevocationCause.PasswordChanged, ttl = 30.minutes, tenantId = None, ttlByClient = ttls)
  private val endedSession = SessionRecord(
    userId = userId,
    clients = List(ClientEntry(clientId, Instant.EPOCH)),
    userAgentId = UserAgentId(UUID.fromString("00000000-0000-0000-0000-0000000000b1")),
    createdAt = Instant.EPOCH,
    amr = Map.empty,
    publicId = PublicSessionId("other-session"),
    expiresAt = Instant.EPOCH,
  )

  private type Stubs = (Stub[PasswordService], Stub[SubmissionLimiter])
  private type Failure = Throwable | ChangePasswordError

  private def serviceTestCase(
      description: String,
      setup: Stubs => UIO[Unit],
      newPassword: String = "NewPass1",
      regex: String = passwordRegex,
      expectedError: Option[Failure] = None,
      otherSessions: OtherSessions = OtherSessions.Keep,
      verify: Stubs => TestResult = _ => assertCompletes,
      verifyLogout: Stub[LogoutService] => TestResult = _ => assertCompletes,
      verifyAudit: List[AuditDraft] => TestResult = _ => assertCompletes,
  ) =
    test(description) {
      for
        passwordService <- ZIO.succeed(stub[PasswordService])
        submissionLimiter <- ZIO.succeed(stub[SubmissionLimiter])
        configuration <- ZIO.succeed(stub[OAuthConfigurationService])
        logout <- ZIO.succeed(stub[LogoutService])
        stubs = (passwordService, submissionLimiter)
        _ <- logout.announceEnded.succeedsWith(())
        _ <- configuration.accessTokenTtls.succeedsWith(ttls)
        _ <- submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
        _ <- submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
        _ <- configuration.getPasswordRegex.succeedsWith(regex)
        _ <- setup(stubs)
        audit = CapturingAuditEvents.shared()
        service = AccountSettingsService.Impl(passwordService, submissionLimiter, configuration, audit, logout)
        result <- service.changePassword(userId, clientId, "OldPass1", newPassword, otherSessions).either
        drafts <- audit.recorded
      yield assertTrue(result.left.toOption == expectedError) && verify(stubs) && verifyLogout(logout) && verifyAudit(drafts)
    }

  val spec = suite("AccountSettingsService")(
    suite("changePassword")(
      serviceTestCase(
        description = "sets the new password when the current one is correct",
        setup = (passwordService, _) =>
          passwordService.verifyPassword.succeedsWith(CheckPassword.Success) *>
            passwordService.setPassword.succeedsWith(Nil),
        verify = (passwordService, _) =>
          assertTrue(
            passwordService.verifyPassword.calls == List((userId, Password("OldPass1"))),
            passwordService.setPassword.calls == List((userId, Password("NewPass1"), PasswordChangeEffect.Untouched)),
          ),
        // account.password_changed: the user is the subject (and, with no actor given, the actor), and neither password is in it
        verifyAudit = drafts =>
          assertTrue(
            drafts.map(_.kind) == List(AuthAuditKinds.AccountPasswordChanged),
            drafts.head.outcome == AuditOutcome.success,
            drafts.head.userId == Some(userId.asInstanceOf[java.util.UUID]),
            drafts.head.details == AuthAuditDetails.Account(via = "account_settings", detail = None),
            !drafts.head.details.toJson.toString.contains("OldPass1"),
            !drafts.head.details.toJson.toString.contains("NewPass1"),
          ),
      ),
      serviceTestCase(
        description = "accepts a temporary password as the current one",
        setup = (passwordService, _) =>
          passwordService.verifyPassword.succeedsWith(CheckPassword.Temporary) *>
            passwordService.setPassword.succeedsWith(Nil),
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
            passwordService.setPassword.succeedsWith(Nil),
      ),
      suite("signing out of the other sessions")(
        serviceTestCase(
          description = "stores the password with the instruction to end every session but the current one, and tells their relying parties",
          otherSessions = OtherSessions.End(Some(currentSession)),
          setup = (passwordService, _) =>
            passwordService.verifyPassword.succeedsWith(CheckPassword.Success) *>
              passwordService.setPassword.succeedsWith(List(endedSession)),
          verify = (passwordService, _) =>
            assertTrue(
              passwordService.setPassword.calls == List((userId, Password("NewPass1"), PasswordChangeEffect.EndOtherSessions(currentSession, terms))),
            ),
          verifyLogout = logout => assertTrue(logout.announceEnded.calls == List((List(endedSession), RevocationCause.PasswordChanged))),
        ),
        serviceTestCase(
          description = "does not touch the sessions when the box is not ticked",
          otherSessions = OtherSessions.Keep,
          setup = (passwordService, _) =>
            passwordService.verifyPassword.succeedsWith(CheckPassword.Success) *>
              passwordService.setPassword.succeedsWith(Nil),
          verify = (passwordService, _) =>
            assertTrue(passwordService.setPassword.calls.map(_._3) == List(PasswordChangeEffect.Untouched)),
          verifyLogout = logout => assertTrue(logout.announceEnded.calls.flatMap(_._1).isEmpty),
        ),
        serviceTestCase(
          description = "is refused, changing nothing, when the request does not say which session is the caller's",
          otherSessions = OtherSessions.End(None),
          setup = (passwordService, _) => passwordService.verifyPassword.succeedsWith(CheckPassword.Success),
          expectedError = Some(ChangePasswordError.CurrentSessionUnknown),
          verify = (passwordService, _) => assertTrue(passwordService.setPassword.calls.isEmpty),
          verifyLogout = logout => assertTrue(logout.announceEnded.calls.isEmpty),
          verifyAudit = drafts => assertTrue(drafts.isEmpty),
        ),
        serviceTestCase(
          description = "ends nothing when the current password is wrong",
          otherSessions = OtherSessions.End(Some(currentSession)),
          setup = (passwordService, _) => passwordService.verifyPassword.succeedsWith(CheckPassword.Failure),
          expectedError = Some(ChangePasswordError.IncorrectCurrentPassword),
          verify = (passwordService, _) => assertTrue(passwordService.setPassword.calls.isEmpty),
          verifyLogout = logout => assertTrue(logout.announceEnded.calls.isEmpty),
        ),
        serviceTestCase(
          description = "ends nothing when the new password is a reuse",
          otherSessions = OtherSessions.End(Some(currentSession)),
          setup = (passwordService, _) =>
            passwordService.verifyPassword.succeedsWith(CheckPassword.Success) *>
              passwordService.setPassword.failsWith(PasswordReuseError(5)),
          expectedError = Some(ChangePasswordError.PasswordReused),
          verifyLogout = logout => assertTrue(logout.announceEnded.calls.isEmpty),
        ),
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
          result <- AccountSettingsService.Impl(passwordService, submissionLimiter, configuration, CapturingAuditEvents.shared(), stub[LogoutService])
            .changePassword(userId, clientId, "OldPass1", "NewPass1", OtherSessions.Keep).either
        yield assertTrue(result == Left(failure))
      },
    ),
  )
