package versola.central.configuration.clients.attestation

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.ClientId
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

/** What a client's attestation policy store is held to, whatever it is kept in: that what is stored is what is read back, builds and
  * all, and that a client has one policy at a time. The clients named here are expected to exist in the store the implementation
  * runs this against; what only a database promises (the policy going with its client) is the implementation's own. */
trait ClientAttestationRepositorySpec extends DatabaseSpecBase[ClientAttestationRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val mobile = ClientId("mobile-a")
  val other = ClientId("mobile-b")
  val t0 = Instant.parse("2026-03-01T00:00:00Z")

  private val ios = IosAttestation(
    appIds = Set("ABCDE12345.com.example.app", "ABCDE12345.com.example.beta"),
    environment = IosEnvironment.development,
  )
  private val android = AndroidApp(packageName = "com.example.app", certificateDigests = Set("ab" * 32, "cd" * 32))
  val enforcing = ClientAttestation(mode = AttestationMode.enforce, ios = Some(ios), android = List(android))
  val reporting = ClientAttestation(mode = AttestationMode.report, ios = None, android = List(android))

  override def testCases(env: ClientAttestationRepositorySpec.Env): List[Spec[ClientAttestationRepositorySpec.Env & Scope, Any]] =
    val repository = env.repository
    List(
      test("a client with no policy has none") {
        for
          found <- repository.find(mobile)
          all <- repository.all
        yield assertTrue(found.isEmpty, all.isEmpty)
      },
      test("what is stored is what is read back, builds and environment included") {
        for
          _ <- repository.put(mobile, enforcing, t0)
          found <- repository.find(mobile)
        yield assertTrue(found == Some(enforcing))
      },
      test("a second put replaces the first rather than adding to it") {
        for
          _ <- repository.put(mobile, enforcing, t0)
          _ <- repository.put(mobile, reporting, t0.plusSeconds(60))
          found <- repository.find(mobile)
        yield assertTrue(found == Some(reporting), found.flatMap(_.ios).isEmpty)
      },
      test("all lists every client's policy, one each") {
        for
          _ <- repository.put(mobile, enforcing, t0)
          _ <- repository.put(other, reporting, t0)
          all <- repository.all
        yield assertTrue(all == Map(mobile -> enforcing, other -> reporting))
      },
      test("delete says whether there was a policy to remove") {
        for
          _ <- repository.put(mobile, enforcing, t0)
          first <- repository.delete(mobile)
          second <- repository.delete(mobile)
          found <- repository.find(mobile)
        yield assertTrue(first, !second, found.isEmpty)
      },
      test("a policy for a client that does not exist is not stored") {
        for result <- repository.put(ClientId("nobody"), enforcing, t0).either
        yield assertTrue(result.isLeft)
      },
    )

object ClientAttestationRepositorySpec:
  case class Env(repository: ClientAttestationRepository)
