package versola.util.audit

import versola.util.UnitSpecBase
import zio.test.*

object AuditSeveritySpec extends UnitSpecBase:

  import AuditOutcome.*
  import AuditSeverity.{high, info, notice}

  private val highResources = List("jwks", "client-secret", "resource-secret", "role", "permission", "edge-key", "user-password", "user-roles")

  val spec = suite("AuditSeverity.of")(
    suite("high")(
      test("a refresh token seen again, a changed audit config and a revoked certificate are high whatever the outcome") {
        val kinds = List(AuditKinds.TokenReuseDetected, AuditKinds.AuditConfigChanged, AuditKinds.CertRevoked)
        assertTrue(kinds.forall(kind => List(success, failure, denied).forall(AuditSeverity.of(kind, _) == high)))
      },
      test("an admin action on a key, a secret, a role or a password is high, a failed one too") {
        val kinds =
          for
            resource <- highResources
            operation <- List("create", "update", "delete", "action:rotate-secret")
          yield AuditKinds.admin(resource, operation)
        assertTrue(kinds.forall(kind => AuditSeverity.of(kind, success) == high && AuditSeverity.of(kind, failure) == high))
      },
    ),
    suite("notice")(
      test("every failure and every denial that is not high is notice") {
        assertTrue(
          AuditSeverity.of(AuditKinds.TokenRefused, failure) == notice,
          AuditSeverity.of(AuditKinds.LoginStep, denied) == notice,
          AuditSeverity.of(AuditKinds.admin("client", "update"), failure) == notice,
          AuditSeverity.of(AuditKinds.admin("client", "update"), denied) == notice,
        )
      },
      test("a revealed secret, a viewed user and a change to how an account is reached are notice when they succeed") {
        val kinds = List(
          AuditKinds.AdminSecretRevealed,
          AuditKinds.AdminUserViewed,
          AuditKinds.AccountPasskeyEnrolled,
          AuditKinds.AccountPasskeyRemoved,
          AuditKinds.AccountPasswordChanged,
          AuditKinds.AccountPasswordSet,
          AuditKinds.AccountPasswordReset,
          AuditKinds.AccountClaimsUpdated,
        )
        assertTrue(kinds.forall(AuditSeverity.of(_, success) == notice))
      },
    ),
    suite("info")(
      test("an ordinary success is info") {
        assertTrue(
          AuditSeverity.of(AuditKinds.TokenIssued, success) == info,
          AuditSeverity.of(AuditKinds.LoginCompleted, success) == info,
          AuditSeverity.of(AuditKinds.admin("client", "create"), success) == info,
          AuditSeverity.of(AuditKinds.admin("user-claims", "update"), success) == info,
        )
      },
    ),
  )
