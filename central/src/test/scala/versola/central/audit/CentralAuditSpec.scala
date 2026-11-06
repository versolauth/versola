package versola.central.audit

import versola.util.audit.{AuditKind, AuditOutcome, AuditSeverity}
import zio.test.*

object CentralAuditSpec extends ZIOSpecDefault:
  import AuditOutcome.*

  private val catalog = CentralAudit.catalog

  def spec = suite("CentralAudit")(
    suite("kinds")(
      test("an admin kind is built from a resource of the closed set and an operation, and nothing else is known") {
        assertTrue(
          CentralAudit.admin("client", "create").value == "admin.client.create",
          catalog.isKnown(CentralAudit.admin("client", "update")),
          catalog.isKnown(CentralAudit.admin("client-secret", "action:rotate-secret")),
          CentralAudit.adminResource(CentralAudit.admin("user-roles", "update")).contains("user-roles"),
          // the explicit events are known, and are not built admin kinds
          catalog.isKnown(CentralAudit.AdminUserViewed),
          catalog.isKnown(CentralAudit.AdminSecretRevealed),
          catalog.isKnown(CentralAudit.AdminAuditQueried),
          catalog.isKnown(CentralAudit.SystemBootstrap),
          CentralAudit.adminResource(CentralAudit.AdminUserViewed).isEmpty,
          // a kind made of what a request carries is never known: a metric label made of it stays bounded
          !catalog.isKnown(AuditKind("admin.made-up.create")),
          !catalog.isKnown(AuditKind("admin.client.explode")),
          !catalog.isKnown(AuditKind("admin.client.action:Rotate Secret")),
          !catalog.isKnown(AuditKind("admin.client.create.extra")),
          !catalog.isKnown(AuditKind("login.completed")),
        )
      },
      test("every operation is create, update, delete or an action named by lowercase letters, digits and dashes") {
        assertTrue(
          List("create", "update", "delete", "action:rotate-secret", "action:set").forall(CentralAudit.isAdminOperation),
          !CentralAudit.isAdminOperation("action:"),
          !CentralAudit.isAdminOperation("action:a b"),
          !CentralAudit.isAdminOperation("patch"),
        )
      },
    ),
    suite("severity")(
      test("an action on what takes over an account, a client or the keys of the system is high whatever its outcome") {
        val high = List("jwks", "client-secret", "resource-secret", "role", "permission", "edge-key", "user-password", "user-roles")
        assertTrue(
          high.forall(resource =>
            List(success, failure, denied).forall(catalog.severity(CentralAudit.admin(resource, "update"), _) == AuditSeverity.high),
          ),
        )
      },
      test("any other action is info when it succeeded and notice when it did not") {
        assertTrue(
          catalog.severity(CentralAudit.admin("client", "update"), success) == AuditSeverity.info,
          catalog.severity(CentralAudit.admin("client", "update"), failure) == AuditSeverity.notice,
          catalog.severity(CentralAudit.admin("client", "delete"), denied) == AuditSeverity.notice,
          catalog.severity(CentralAudit.AdminAuditQueried, success) == AuditSeverity.info,
          catalog.severity(CentralAudit.SystemBootstrap, success) == AuditSeverity.info,
        )
      },
      test("a secret handed back and a look at a person are notice even when they succeeded") {
        assertTrue(
          catalog.severity(CentralAudit.AdminSecretRevealed, success) == AuditSeverity.notice,
          catalog.severity(CentralAudit.AdminUserViewed, success) == AuditSeverity.notice,
        )
      },
    ),
  )
