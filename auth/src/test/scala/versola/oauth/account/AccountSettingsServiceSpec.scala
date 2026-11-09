package versola.oauth.account

import org.scalamock.stubs.Stub
import versola.audit.{AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents}
import versola.auth.model.Password
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.{CheckPassword, PasswordChangeEffect, PasswordReuseError}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.client.model.TenantId
import versola.oauth.conversation.limit.{Acquisition, ChallengeThrottleRecord, ChallengeType, LimitStatus, Reservation, SubmissionLimiter}
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

  // What `SubmissionLimiter.acquireAll` hands back for an attempt it has charged: the service gives it back on a right answer.
  private val charges = List(
    Reservation.Granted(
      tenantId = TenantId("default"),
      subject = userId.toString,
      challengeType = ChallengeType.PasswordSubmit,
      after = LimitStatus.Allowed,
      reservedAt = Instant.EPOCH,
      previous = None,
      applied = ChallengeThrottleRecord(
        tenantId = TenantId("default"),
        subject = userId.toString,
        challengeType = ChallengeType.PasswordSubmit,
        attempts = List(0L),
        bannedUntil = None,
        expiresAt = Instant.EPOCH.plusSeconds(900),
      ),
    ),
  )

  /** A limiter that counts like the real one: claiming an attempt is one atomic step, and past `maxAttempts` claims it denies. It
    * also answers the old read-then-record calls, from the same counter, so a service that still checks first and records
    * afterwards runs against it (and is let through by the read).
    */
  private final class CountingLimiter(maxAttempts: Int, used: Ref[Int], refunded: Ref[Int]) extends SubmissionLimiter:
    private def unused = ZIO.dieMessage("not used by the account service")
    override def isBanned(clientId: ClientId, subject: String, challengeType: ChallengeType) =
      used.get.map(n => if n >= maxAttempts then LimitStatus.Banned else LimitStatus.Allowed)
    override def recordLimit(clientId: ClientId, subject: String, challengeType: ChallengeType) =
      used.updateAndGet(_ + 1).map(n => if n >= maxAttempts then LimitStatus.Banned else LimitStatus.Allowed)
    override def acquireAll(clientId: ClientId, subjects: List[String], challengeType: ChallengeType) =
      used.modify: n =>
        if n >= maxAttempts then Acquisition.Denied(LimitStatus.Banned) -> n
        else Acquisition.Granted(LimitStatus.Allowed, charges) -> (n + 1)
    override def refund(granted: List[Reservation.Granted]) = used.update(_ - 1) *> refunded.update(_ + 1)
    override def statusFor(clientId: ClientId, subject: String, challengeTypes: List[ChallengeType]) = unused
    override def statusForSubjects(clientId: ClientId, subjects: List[String], challengeType: ChallengeType) = unused
    override def tryAcquire(clientId: ClientId, subject: String, challengeType: ChallengeType) = unused
    override def recordLimitAll(clientId: ClientId, subjects: List[String], challengeType: ChallengeType) = unused
    override def reset(clientId: ClientId, subject: String, challengeType: ChallengeType) = unused

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
        _ <- submissionLimiter.acquireAll.succeedsWith(Acquisition.Granted(LimitStatus.Allowed, charges))
        _ <- submissionLimiter.refund.succeedsWith(())
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
        verify = (passwordService, submissionLimiter) =>
          assertTrue(
            passwordService.verifyPassword.calls == List((userId, Password("OldPass1"))),
            passwordService.setPassword.calls == List((userId, Password("NewPass1"), PasswordChangeEffect.Untouched)),
            // claimed on the user's own subject, the one the login form's password step charges, and given back: a right password spends none
            submissionLimiter.acquireAll.calls == List((clientId, List(userId.toString), ChallengeType.PasswordSubmit)),
            submissionLimiter.refund.calls == List(charges),
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
        verify = (_, submissionLimiter) => assertTrue(submissionLimiter.refund.calls == List(charges)),
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
                submissionLimiter.acquireAll.calls == List((clientId, List(userId.toString), ChallengeType.PasswordSubmit)),
                // the claimed attempt stays charged
                submissionLimiter.refund.calls.isEmpty,
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
        setup = (_, submissionLimiter) => submissionLimiter.acquireAll.succeedsWith(Acquisition.Denied(LimitStatus.RateLimited(30))),
        expectedError = Some(ChangePasswordError.TooManyAttempts(Some(30))),
        verify = (passwordService, submissionLimiter) =>
          assertTrue(passwordService.verifyPassword.calls.isEmpty, submissionLimiter.refund.calls.isEmpty),
        verifyAudit = drafts => assertTrue(drafts.map(d => (d.outcome, d.reason)) == List((AuditOutcome.denied, Some("too_many_attempts")))),
      ),
      serviceTestCase(
        description = "is refused without a wait when the user is banned",
        setup = (_, submissionLimiter) => submissionLimiter.acquireAll.succeedsWith(Acquisition.Denied(LimitStatus.Banned)),
        expectedError = Some(ChangePasswordError.TooManyAttempts(None)),
        verify = (passwordService, _) => assertTrue(passwordService.verifyPassword.calls.isEmpty),
      ),
      serviceTestCase(
        description = "refuses a new password the tenant's policy does not allow, without touching the stored one",
        newPassword = "short",
        setup = (passwordService, _) => passwordService.verifyPassword.succeedsWith(CheckPassword.Success),
        expectedError = Some(ChangePasswordError.NewPasswordNotAllowed),
        verify = (passwordService, submissionLimiter) =>
          assertTrue(passwordService.setPassword.calls.isEmpty, submissionLimiter.refund.calls == List(charges)),
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
      test("cuts parallel guesses of the current password off at the configured number of attempts") {
        val maxAttempts = 3
        val requests = 10
        for
          used <- Ref.make(0)
          refunded <- Ref.make(0)
          verified <- Ref.make(0)
          passwordService <- ZIO.succeed(stub[PasswordService])
          configuration <- ZIO.succeed(stub[OAuthConfigurationService])
          logout <- ZIO.succeed(stub[LogoutService])
          _ <- configuration.getPasswordRegex.succeedsWith(passwordRegex)
          // The guess is checked only after the other requests had their turn: what is claimed before the check, and not after
          // it, is what stops them.
          _ = passwordService.verifyPassword.returnsWith(
            ZIO.yieldNow.repeatN(20) *> verified.update(_ + 1).as(CheckPassword.Failure),
          )
          service = AccountSettingsService.Impl(
            passwordService,
            CountingLimiter(maxAttempts, used, refunded),
            configuration,
            CapturingAuditEvents.shared(),
            logout,
          )
          results <- ZIO.foreachPar(List.fill(requests)(()))(_ =>
            service.changePassword(userId, clientId, "wrong", "NewPass1", OtherSessions.Keep).either,
          )
          checked <- verified.get
        yield assertTrue(
          checked == maxAttempts,
          results.count(_ == Left(ChangePasswordError.IncorrectCurrentPassword)) == maxAttempts,
          results.count(_ == Left(ChangePasswordError.TooManyAttempts(None))) == requests - maxAttempts,
          passwordService.setPassword.calls.isEmpty,
        )
      },
      test("a right password spends none of the budget, so a wrong guess after it still has all of it") {
        val maxAttempts = 2
        for
          used <- Ref.make(0)
          refunded <- Ref.make(0)
          passwordService <- ZIO.succeed(stub[PasswordService])
          configuration <- ZIO.succeed(stub[OAuthConfigurationService])
          logout <- ZIO.succeed(stub[LogoutService])
          _ <- logout.announceEnded.succeedsWith(())
          _ <- configuration.getPasswordRegex.succeedsWith(passwordRegex)
          _ <- passwordService.verifyPassword.succeedsWith(CheckPassword.Success)
          _ <- passwordService.setPassword.succeedsWith(Nil)
          service = AccountSettingsService.Impl(
            passwordService,
            CountingLimiter(maxAttempts, used, refunded),
            configuration,
            CapturingAuditEvents.shared(),
            logout,
          )
          _ <- ZIO.foreachDiscard(1 to 5)(_ => service.changePassword(userId, clientId, "OldPass1", "NewPass1", OtherSessions.Keep))
          spent <- used.get
          givenBack <- refunded.get
        yield assertTrue(spent == 0, givenBack == 5)
      },
      test("passes an infrastructure failure through as it is") {
        val failure = IllegalStateException("db down")
        for
          passwordService <- ZIO.succeed(stub[PasswordService])
          submissionLimiter <- ZIO.succeed(stub[SubmissionLimiter])
          configuration <- ZIO.succeed(stub[OAuthConfigurationService])
          _ <- submissionLimiter.acquireAll.succeedsWith(Acquisition.Granted(LimitStatus.Allowed, charges))
          _ <- submissionLimiter.refund.succeedsWith(())
          _ <- configuration.getPasswordRegex.succeedsWith(passwordRegex)
          _ <- passwordService.verifyPassword.succeedsWith(CheckPassword.Success)
          _ <- passwordService.setPassword.failsWith(failure)
          result <- AccountSettingsService.Impl(passwordService, submissionLimiter, configuration, CapturingAuditEvents.shared(), stub[LogoutService])
            .changePassword(userId, clientId, "OldPass1", "NewPass1", OtherSessions.Keep).either
        yield assertTrue(result == Left(failure))
      },
    ),
  )
