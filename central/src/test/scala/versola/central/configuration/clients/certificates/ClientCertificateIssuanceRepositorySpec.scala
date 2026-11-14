package versola.central.configuration.clients.certificates

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.{ClientId, OAuthClientRepository}
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

/** What every [[ClientCertificateIssuanceRepository]] promises: one issuance a client, replaced by the next; the issuances that
  * expire inside a window, soonest first; and an issuance that exists only for a client that does, and goes with it. The two
  * clients the cases use are registered by the storage's runner before each case. */
trait ClientCertificateIssuanceRepositorySpec extends DatabaseSpecBase[ClientCertificateIssuanceRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import ClientCertificateIssuanceRepositorySpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    val repository = env.repository
    List(
      test("upsert records an issuance and a second one replaces it") {
        for
          _ <- repository.upsert(ClientCertificateIssuance(clientId, "01", t0.plusSeconds(100), t0))
          _ <- repository.upsert(ClientCertificateIssuance(clientId, "02", t0.plusSeconds(500), t0.plusSeconds(10)))
          found <- repository.find(clientId)
        yield assertTrue(found.map(_.serial) == Some("02"), found.map(_.notAfter) == Some(t0.plusSeconds(500)))
      },
      test("expiringBefore returns only the issuances inside the window, soonest first") {
        for
          _ <- repository.upsert(ClientCertificateIssuance(clientId, "01", t0.plusSeconds(500), t0))
          _ <- repository.upsert(ClientCertificateIssuance(other, "02", t0.plusSeconds(100), t0))
          due <- repository.expiringBefore(t0.plusSeconds(300))
          all <- repository.expiringBefore(t0.plusSeconds(1000))
        yield assertTrue(due.map(_.clientId) == Vector(other), all.map(_.clientId) == Vector(other, clientId))
      },
      test("delete forgets an issuance") {
        for
          _ <- repository.upsert(ClientCertificateIssuance(clientId, "01", t0, t0))
          _ <- repository.delete(clientId)
          found <- repository.find(clientId)
        yield assertTrue(found.isEmpty)
      },
      test("replace updates an existing issuance and never creates one") {
        for
          missing <- repository.replace(ClientCertificateIssuance(clientId, "09", t0, t0))
          _ <- repository.upsert(ClientCertificateIssuance(clientId, "01", t0, t0))
          replaced <- repository.replace(ClientCertificateIssuance(clientId, "02", t0.plusSeconds(60), t0))
          found <- repository.find(clientId)
        yield assertTrue(!missing, replaced, found.map(_.serial) == Some("02"))
      },
      test("deleting the client removes its issuance") {
        for
          _ <- repository.upsert(ClientCertificateIssuance(clientId, "01", t0, t0))
          _ <- env.clients.deleteClient(clientId)
          found <- repository.find(clientId)
        yield assertTrue(found.isEmpty)
      },
      test("an issuance cannot exist for a client that does not") {
        for
          exit <- repository.upsert(ClientCertificateIssuance(ClientId("ghost"), "01", t0, t0)).exit
        yield assertTrue(exit.isFailure)
      },
    )

object ClientCertificateIssuanceRepositorySpec:

  /** @param clients
    *   the repository of the clients the issuances belong to: [[clientId]] and [[other]] are in it when a case starts
    */
  case class Env(repository: ClientCertificateIssuanceRepository, clients: OAuthClientRepository)

  val clientId: ClientId = ClientId("mobile-app")
  val other: ClientId = ClientId("other-app")
  val t0: Instant = Instant.parse("2026-03-01T00:00:00Z")
