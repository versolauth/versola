package versola.central.configuration.clients.attestation

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.clients.attestation.AttestationFixtures.*
import versola.central.configuration.clients.{ClientId, OAuthClientService}
import zio.*
import zio.test.*

/** Who may have a policy, what is stored, and what an edge is told. */
object ClientAttestationServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val mobile = ClientId("mobile")
  private val web = ClientId("web")

  private def service =
    val repository = stub[ClientAttestationRepository]
    val clients = stub[OAuthClientService]
    (repository, clients, ClientAttestationService.Impl(repository, clients))

  private def refusalOf[A](effect: Task[A]): UIO[Option[ClientAttestationService.Refusal]] =
    effect.either.map:
      case Left(error: ClientAttestationService.Refused) => Some(error.kind)
      case _ => None

  def spec = suite("ClientAttestationService")(
    test("a client with no policy reads as off") {
      val (repository, clients, impl) = service
      for
        _ <- clients.findClient.succeedsWith(Some(nativeClient(mobile)))
        _ <- repository.find.succeedsWith(None)
        found <- impl.find(mobile)
      yield assertTrue(found == ClientAttestation.off)
    },
    test("a policy is stored as validated, with the digests in one form") {
      val (repository, clients, impl) = service
      val upper = enforcing.copy(android = List(android.copy(certificateDigests = Set(digest.toUpperCase))))
      for
        _ <- clients.findClient.succeedsWith(Some(nativeClient(mobile)))
        _ <- repository.put.succeedsWith(())
        stored <- impl.put(mobile, upper)
      yield assertTrue(
        stored == enforcing,
        repository.put.calls.map((id, policy, _) => id -> policy) == List(mobile -> enforcing),
      )
    },
    test("only a native client that authenticates with tls_client_auth may have one, since only its /native/start asks") {
      val (repository, clients, impl) = service
      for
        _ <- clients.findClient.returnsZIO(id => ZIO.succeed(Some(if id == mobile then nativeClient(id) else webClient(id))))
        notNative <- refusalOf(impl.put(web, enforcing))
        read <- refusalOf(impl.find(web))
      yield assertTrue(
        notNative == Some(ClientAttestationService.Refusal.NotNative),
        read == Some(ClientAttestationService.Refusal.NotNative),
        repository.put.calls.isEmpty,
      )
    },
    test("an unknown client is refused as such") {
      val (_, clients, impl) = service
      for
        _ <- clients.findClient.succeedsWith(None)
        put <- refusalOf(impl.put(mobile, enforcing))
        deleted <- refusalOf(impl.delete(mobile))
      yield assertTrue(
        put == Some(ClientAttestationService.Refusal.UnknownClient),
        deleted == Some(ClientAttestationService.Refusal.UnknownClient),
      )
    },
    test("a client deleted while the policy is being stored is not found, and not a failure of central") {
      val (repository, clients, impl) = service
      val violation = java.sql.SQLException("fk violation", "23503")
      for
        // The cache the lookup reads still has the client; the foreign key knows it is gone.
        _ <- clients.findClient.succeedsWith(Some(nativeClient(mobile)))
        _ <- repository.put.failsWith(RuntimeException("wrapped", violation))
        refused <- refusalOf(impl.put(mobile, enforcing))
      yield assertTrue(refused == Some(ClientAttestationService.Refusal.UnknownClient))
    },
    test("any other failure of the repository is not turned into a refusal") {
      val (repository, clients, impl) = service
      for
        _ <- clients.findClient.succeedsWith(Some(nativeClient(mobile)))
        _ <- repository.put.failsWith(RuntimeException("boom"))
        refused <- refusalOf(impl.put(mobile, enforcing))
      yield assertTrue(refused.isEmpty)
    },
    test("a policy that cannot be stored is refused before the repository is asked") {
      val (repository, clients, impl) = service
      for
        _ <- clients.findClient.succeedsWith(Some(nativeClient(mobile)))
        refused <- refusalOf(impl.put(mobile, ClientAttestation.off.copy(mode = AttestationMode.enforce)))
      yield assertTrue(refused == Some(ClientAttestationService.Refusal.Invalid), repository.put.calls.isEmpty)
    },
    test("a policy can be taken away from a client that is no longer a native one") {
      val (repository, clients, impl) = service
      for
        _ <- clients.findClient.succeedsWith(Some(webClient(web)))
        _ <- repository.delete.succeedsWith(true)
        _ <- impl.delete(web)
      yield assertTrue(repository.delete.calls == List(web))
    },
    test("an edge is told the policies that are on, and not the ones that are off") {
      val (repository, _, impl) = service
      val off = ClientAttestation.off.copy(ios = Some(ios))
      for
        _ <- repository.all.succeedsWith(Map(mobile -> enforcing, ClientId("prepared") -> off))
        active <- impl.active
      yield assertTrue(active == Map(mobile -> enforcing))
    },
  )
