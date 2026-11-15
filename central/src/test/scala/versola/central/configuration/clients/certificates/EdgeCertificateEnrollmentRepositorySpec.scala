package versola.central.configuration.clients.certificates

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.{ClientId, OAuthClientRepository}
import versola.central.configuration.edges.EdgeId
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

/** What every [[EdgeCertificateEnrollmentRepository]] promises: a client is enrolled once, what was last signed for it is
  * recorded with the edge it was signed for, and an enrolment exists only for a client that does, and goes with it. The two
  * clients the cases use are registered by the storage's runner before each case; what was last signed, which no method of the
  * trait reads back, comes through [[EdgeCertificateEnrollmentRepositorySpec.Fixtures]]. */
trait EdgeCertificateEnrollmentRepositorySpec extends DatabaseSpecBase[EdgeCertificateEnrollmentRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import EdgeCertificateEnrollmentRepositorySpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    val repository = env.repository
    List(
      test("enrolls a client once, however often it is asked") {
        for
          before <- repository.isEnrolled(clientId)
          _ <- repository.enroll(clientId, t0)
          _ <- repository.enroll(clientId, t0.plusSeconds(60))
          after <- repository.isEnrolled(clientId)
          all <- repository.enrolledClients
        yield assertTrue(!before, after, all == Set(clientId))
      },
      test("records what was last signed, and for which edge") {
        for
          _ <- repository.enroll(clientId, t0)
          _ <- repository.recordIssued(clientId, "ab12", t0.plusSeconds(5), EdgeId("edge-1"))
          issued <- env.fixtures.lastIssued(clientId)
        yield assertTrue(issued == List(("ab12", "edge-1")))
      },
      test("deleting the client removes its enrolment") {
        for
          _ <- repository.enroll(clientId, t0)
          _ <- env.clients.deleteClient(clientId)
          enrolled <- repository.isEnrolled(clientId)
        yield assertTrue(!enrolled)
      },
      test("a client that does not exist cannot be enrolled") {
        for
          exit <- repository.enroll(ClientId("ghost"), t0).exit
        yield assertTrue(exit.isFailure)
      },
    )

object EdgeCertificateEnrollmentRepositorySpec:

  /** @param clients
    *   the repository of the clients the enrolments belong to: [[clientId]] and [[other]] are in it when a case starts
    */
  case class Env(repository: EdgeCertificateEnrollmentRepository, clients: OAuthClientRepository, fixtures: Fixtures)

  /** What a case reads of what the repository wrote and no method of the trait returns: the storage's runner supplies it. */
  trait Fixtures:
    /** The serial last signed for the client and the id of the edge it was signed for, one pair for each enrolment of the client. */
    def lastIssued(clientId: ClientId): Task[List[(String, String)]]

  val clientId: ClientId = ClientId("mobile-app")
  val other: ClientId = ClientId("other-app")
  val t0: Instant = Instant.parse("2026-03-01T00:00:00Z")
