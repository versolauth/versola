package versola.util.audit

import versola.util.UnitSpecBase
import zio.json.*
import zio.test.*

object AuditKindsSpec extends UnitSpecBase:

  private val constants: List[AuditKind] = List(
    AuditKinds.LoginStep,
    AuditKinds.LoginCompleted,
    AuditKinds.AuthorizeRequest,
    AuditKinds.ParRequest,
    AuditKinds.ConsentGranted,
    AuditKinds.ConsentDenied,
    AuditKinds.ConsentRevoked,
    AuditKinds.TokenIssued,
    AuditKinds.TokenRefused,
    AuditKinds.TokenReuseDetected,
    AuditKinds.TokenRevoked,
    AuditKinds.ClientAuthFailed,
    AuditKinds.SessionEnded,
    AuditKinds.AccountRegistered,
    AuditKinds.AccountPasswordChanged,
    AuditKinds.AccountPasswordSet,
    AuditKinds.AccountPasswordReset,
    AuditKinds.AccountPasskeyEnrolled,
    AuditKinds.AccountPasskeyRemoved,
    AuditKinds.AccountClaimsUpdated,
    AuditKinds.AccountLimitsReset,
    AuditKinds.CertEnrolled,
    AuditKinds.CertRenewed,
    AuditKinds.CertRevoked,
    AuditKinds.AdminUserViewed,
    AuditKinds.AdminSecretRevealed,
    AuditKinds.AdminAuditQueried,
    AuditKinds.AuditConfigChanged,
    AuditKinds.SystemBootstrap,
  )

  val spec = suite("AuditKinds")(
    test("every constant is unique and listed in the vocabulary") {
      assertTrue(
        constants.distinct.size == constants.size,
        AuditKinds.all == constants.toSet,
      )
    },
    test("every listed kind is dotted lower case, so that it is one label value and one column value") {
      assertTrue(AuditKinds.all.forall(kind => kind.value.matches("[a-z]+(\\.[a-z_]+)+")))
    },
    suite("isKnown")(
      test("a listed kind is known") {
        assertTrue(AuditKinds.isKnown("token.issued"), AuditKinds.isKnown("admin.user.viewed"))
      },
      test("a built admin kind is known for every resource and operation") {
        val built =
          for
            resource <- AuditKinds.adminResources.toList
            operation <- List("create", "update", "delete", "action:rotate-secret", "action:flush")
          yield AuditKinds.admin(resource, operation)
        assertTrue(built.forall(kind => AuditKinds.isKnown(kind.value)))
      },
      test("a built admin kind with an unknown resource or operation is not known") {
        assertTrue(
          !AuditKinds.isKnown("admin.spaceship.create"),
          !AuditKinds.isKnown("admin.client.explode"),
          !AuditKinds.isKnown("admin.client.action:"),
          !AuditKinds.isKnown("admin.client.action:Rotate Secret"),
          !AuditKinds.isKnown("admin.client"),
        )
      },
      test("a kind that is not listed is not known") {
        assertTrue(!AuditKinds.isKnown("token.stolen"), !AuditKinds.isKnown(""))
      },
    ),
    test("the resource of a built admin kind is read back, and an explicit kind has none") {
      assertTrue(
        AuditKinds.adminResource(AuditKinds.admin("client-secret", "create")) == Some("client-secret"),
        AuditKinds.adminResource(AuditKinds.admin("user", "action:revoke")) == Some("user"),
        AuditKinds.adminResource(AuditKinds.TokenIssued).isEmpty,
        AuditKinds.adminResource(AuditKinds.AdminUserViewed).isEmpty,
      )
    },
    test("a kind is its string on the wire") {
      assertTrue(AuditKinds.TokenIssued.toJson == "\"token.issued\"", "\"token.issued\"".fromJson[AuditKind] == Right(AuditKinds.TokenIssued))
    },
  )
