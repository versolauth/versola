package versola.central.configuration.clients.certificates

import versola.central.audit.TestCertAuditState
import versola.central.configuration.clients.ClientId
import zio.test.*

object RenewalFailuresSpec extends ZIOSpecDefault:

  private val a = ClientId("app-a")
  private val b = ClientId("app-b")

  def spec = suite("RenewalFailures")(
    test("a failure is news the first time, and not again for the same reason") {
      val failures = TestCertAuditState.renewalFailures()
      for
        first <- failures.isNew(a, "issuer_unreachable")
        again <- failures.isNew(a, "issuer_unreachable")
        other <- failures.isNew(b, "issuer_unreachable")
      yield assertTrue(first, !again, other)
    },
    test("another reason is news, and then it is the one that is not") {
      val failures = TestCertAuditState.renewalFailures()
      for
        _ <- failures.isNew(a, "issuer_unreachable")
        changed <- failures.isNew(a, "issuer_refused")
        same <- failures.isNew(a, "issuer_refused")
        back <- failures.isNew(a, "issuer_unreachable")
      yield assertTrue(changed, !same, back)
    },
    test("a client that recovered is news again when it fails") {
      val failures = TestCertAuditState.renewalFailures()
      for
        _ <- failures.isNew(a, "issuer_unreachable")
        _ <- failures.recovered(a)
        known <- failures.failing
        again <- failures.isNew(a, "issuer_unreachable")
      yield assertTrue(known.isEmpty, again)
    },
    test("only the clients that are still due are kept") {
      val failures = TestCertAuditState.renewalFailures()
      for
        _ <- failures.isNew(a, "issuer_unreachable")
        _ <- failures.isNew(b, "issuer_unreachable")
        _ <- failures.retainOnly(Set(b))
        known <- failures.failing
        gone <- failures.isNew(a, "issuer_unreachable")
      yield assertTrue(known.keySet == Set(b), gone)
    },
  )
