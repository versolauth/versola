package versola.util.audit

import versola.util.UnitSpecBase
import zio.json.*
import zio.test.*

object AuditCatalogSpec extends UnitSpecBase:

  import AuditOutcome.*

  private val login = AuditKind("thing.login")
  private val attack = AuditKind("thing.attack")
  private val secret = AuditKind("thing.secret_viewed")
  private val unlisted = AuditKind("thing.unlisted")

  private val catalog = AuditCatalog.of(kinds = Set(login, attack, secret), high = Set(attack), notice = Set(secret))

  val spec = suite("AuditCatalog")(
    suite("isKnown")(
      test("a listed kind is known, and a kind that is not listed is not") {
        assertTrue(catalog.isKnown(login), catalog.isKnown(attack), !catalog.isKnown(unlisted), !catalog.isKnown(AuditKind("")))
      },
      test("a service that builds kinds from parts says which it knows") {
        val built = new AuditCatalog:
          override def isKnown(kind: AuditKind): Boolean = kind.value.matches("area\\.(a|b)\\.(create|update)")
        assertTrue(built.isKnown(AuditKind("area.a.create")), !built.isKnown(AuditKind("area.c.create")), !built.isKnown(AuditKind("area.a.explode")))
      },
    ),
    suite("severity")(
      test("a high kind is high whatever the outcome") {
        assertTrue(List(success, failure, denied).forall(catalog.severity(attack, _) == AuditSeverity.high))
      },
      test("a failure or a denial of any other kind is notice") {
        assertTrue(
          catalog.severity(login, failure) == AuditSeverity.notice,
          catalog.severity(login, denied) == AuditSeverity.notice,
          catalog.severity(unlisted, failure) == AuditSeverity.notice,
        )
      },
      test("a notice kind is notice when it succeeds too") {
        assertTrue(catalog.severity(secret, success) == AuditSeverity.notice)
      },
      test("an ordinary success is info") {
        assertTrue(catalog.severity(login, success) == AuditSeverity.info, catalog.severity(unlisted, success) == AuditSeverity.info)
      },
      test("a catalog that says nothing is high or notice rates by the outcome alone") {
        val plain = AuditCatalog.of(Set(login))
        assertTrue(plain.severity(login, success) == AuditSeverity.info, plain.severity(login, denied) == AuditSeverity.notice)
      },
    ),
    suite("wire form")(
      test("a kind, a source and an actor type are their strings, and the enums their case names") {
        assertTrue(
          login.toJson == "\"thing.login\"",
          "\"thing.login\"".fromJson[AuditKind] == Right(login),
          AuditSource("some-service").toJson == "\"some-service\"",
          ActorType.user.toJson == "\"user\"",
          AuditOutcome.denied.toJson == "\"denied\"",
          AuditSeverity.high.toJson == "\"high\"",
          "\"nonsense\"".fromJson[AuditOutcome].isLeft,
          "\"nonsense\"".fromJson[AuditSeverity].isLeft,
        )
      },
    ),
  )
