package versola.audit

import versola.util.UnitSpecBase
import versola.util.audit.{AuditKind, AuditOutcome, AuditSeverity}
import zio.test.*

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** What auth says about its own events: the kinds it lists, how serious each is, and that every kind a call site names is one of them. */
object AuthAuditSpec extends UnitSpecBase:

  private val constants: List[AuditKind] =
    AuthAuditKinds.getClass.getDeclaredMethods.toList
      .filter(m => m.getParameterCount == 0 && m.getReturnType == classOf[String])
      .map(m => AuditKind(m.invoke(AuthAuditKinds).asInstanceOf[String]))

  def spec = suite("AuthAudit")(
    test("every kind constant is in the list, and the kinds are unique dotted names") {
      val names = constants.map(_.value)
      assertTrue(
        constants.size == AuthAuditKinds.all.size,
        constants.toSet == AuthAuditKinds.all,
        names.distinct == names,
        names.forall(_.matches("[a-z]+(\\.[a-z_]+)+")),
      )
    },
    test("the catalog knows what auth emits and nothing else: a kind built from a request is not a label") {
      assertTrue(
        AuthAuditKinds.all.forall(AuthAudit.catalog.isKnown),
        !AuthAudit.catalog.isKnown(AuditKind("admin.client.create")),
        !AuthAudit.catalog.isKnown(AuditKind("token.issued; drop table")),
      )
    },
    test("a replayed token and a revoked certificate are high, whatever the outcome") {
      assertTrue(
        AuthAudit.catalog.severity(AuthAuditKinds.TokenReuseDetected, AuditOutcome.denied) == AuditSeverity.high,
        AuthAudit.catalog.severity(AuthAuditKinds.TokenReuseDetected, AuditOutcome.success) == AuditSeverity.high,
        AuthAudit.catalog.severity(AuthAuditKinds.CertRevoked, AuditOutcome.success) == AuditSeverity.high,
      )
    },
    test("what changes how an account is reached is notice when it succeeds, and every failure or denial is notice") {
      val account = List(
        AuthAuditKinds.AccountPasswordChanged,
        AuthAuditKinds.AccountPasswordSet,
        AuthAuditKinds.AccountPasswordReset,
        AuthAuditKinds.AccountPasskeyEnrolled,
        AuthAuditKinds.AccountPasskeyRemoved,
        AuthAuditKinds.AccountClaimsUpdated,
      )
      assertTrue(
        account.forall(kind => AuthAudit.catalog.severity(kind, AuditOutcome.success) == AuditSeverity.notice),
        AuthAudit.catalog.severity(AuthAuditKinds.LoginStep, AuditOutcome.failure) == AuditSeverity.notice,
        AuthAudit.catalog.severity(AuthAuditKinds.TokenRefused, AuditOutcome.failure) == AuditSeverity.notice,
        AuthAudit.catalog.severity(AuthAuditKinds.ClientAuthFailed, AuditOutcome.denied) == AuditSeverity.notice,
        AuthAudit.catalog.severity(AuthAuditKinds.LoginStep, AuditOutcome.denied) == AuditSeverity.notice,
      )
    },
    test("the ordinary events are info") {
      assertTrue(
        List(
          AuthAuditKinds.LoginStep,
          AuthAuditKinds.LoginCompleted,
          AuthAuditKinds.TokenIssued,
          AuthAuditKinds.SessionEnded,
          AuthAuditKinds.ConsentGranted,
          AuthAuditKinds.AccountRegistered,
        )
          .forall(kind => AuthAudit.catalog.severity(kind, AuditOutcome.success) == AuditSeverity.info),
      )
    },
    test("every kind the sources of auth emit is one of its kinds, and every kind but cert.revoked is emitted somewhere") {
      val root = List("auth", "../auth", "../../auth").map(Paths.get(_)).find(p => Files.isDirectory(p.resolve("src/main/scala"))).get
      val sources = Files.walk(root.resolve("src/main/scala")).iterator().asScala
        .filter(_.toString.endsWith(".scala")).filterNot(_.toString.endsWith("AuthAudit.scala"))
        .map(Files.readString).mkString("\n")
      val named = "AuthAuditKinds\\.(\\w+)".r.findAllMatchIn(sources).map(_.group(1)).toSet
      val declared = constants.map(_.value).zip(AuthAuditKinds.getClass.getDeclaredMethods.toList.filter(m =>
        m.getParameterCount == 0 && m.getReturnType == classOf[String],
      ).map(_.getName))
      val unknown = named -- declared.map(_._2).toSet
      val emitted = declared.filter((_, name) => named.contains(name)).map(_._1).toSet
      assertTrue(unknown.isEmpty, (AuthAuditKinds.all.map(_.value) -- emitted) == Set("cert.revoked"))
    },
  )
