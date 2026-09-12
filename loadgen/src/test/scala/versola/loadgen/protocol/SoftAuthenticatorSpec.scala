package versola.loadgen.protocol

import com.yubico.webauthn.data.*
import com.yubico.webauthn.{
  CredentialRepository,
  FinishAssertionOptions,
  FinishRegistrationOptions,
  RegisteredCredential,
  RelyingParty,
  StartAssertionOptions,
  StartRegistrationOptions,
}
import zio.json.*
import zio.test.*
import zio.ZIO

import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Verifies [[SoftAuthenticator]] against the same yubico `webauthn-server-core` the SUT's
  * `WebAuthnService` runs (#271's unit-test requirement): if the library accepts these
  * ceremonies here, a passkey login measured against the real auth is measuring the SUT and not
  * a hand-rolled CBOR bug.
  */
object SoftAuthenticatorSpec extends ZIOSpecDefault:

  private val rpId = "versola.test"
  private val origin = "https://versola.test"
  private val userId = UUID.fromString("11111111-2222-3333-4444-555555555555")

  private def userHandle(id: UUID): ByteArray =
    val buffer = ByteBuffer.allocate(16)
    buffer.putLong(id.getMostSignificantBits)
    buffer.putLong(id.getLeastSignificantBits)
    ByteArray(buffer.array())

  /** The SUT's passkey table, as far as a single credential is concerned. The yubico API calls
    * back synchronously, so this is a plain atomic rather than a `Ref`.
    */
  private final class InMemoryCredentials extends CredentialRepository:
    private val stored = AtomicReference(Option.empty[RegisteredCredential])

    private def get: Option[RegisteredCredential] = stored.get()

    def put(credential: RegisteredCredential): Unit = stored.set(Some(credential))

    override def getCredentialIdsForUsername(username: String): java.util.Set[PublicKeyCredentialDescriptor] =
      get.map(credential => PublicKeyCredentialDescriptor.builder().id(credential.getCredentialId).build()).toSet.asJava

    override def getUserHandleForUsername(username: String): java.util.Optional[ByteArray] =
      Some(userHandle(UUID.fromString(username))).toJava

    override def getUsernameForUserHandle(handle: ByteArray): java.util.Optional[String] =
      val buffer = ByteBuffer.wrap(handle.getBytes)
      Some(UUID(buffer.getLong, buffer.getLong).toString).toJava

    override def lookup(credentialId: ByteArray, handle: ByteArray): java.util.Optional[RegisteredCredential] =
      get.filter(_.getCredentialId == credentialId).toJava

    override def lookupAll(credentialId: ByteArray): java.util.Set[RegisteredCredential] =
      get.filter(_.getCredentialId == credentialId).toSet.asJava

  private def relyingParty(credentials: CredentialRepository): RelyingParty =
    RelyingParty
      .builder()
      .identity(RelyingPartyIdentity.builder().id(rpId).name("Versola").build())
      .credentialRepository(credentials)
      .origins(Set(origin).asJava)
      .build()

  private def isMalformed(error: ProtocolError): Boolean = error match
    case ProtocolError.MalformedResponse(_, _) => true
    case _ => false

  def spec = suite("SoftAuthenticator")(
    test("its registration and assertion both verify against the yubico library the SUT uses") {
      for
        repository <- ZIO.succeed(InMemoryCredentials())
        rp = relyingParty(repository)
        creationOptions = rp.startRegistration(
          StartRegistrationOptions
            .builder()
            .user(UserIdentity.builder().name(userId.toString).displayName("virtual user").id(userHandle(userId)).build())
            .authenticatorSelection(
              AuthenticatorSelectionCriteria
                .builder()
                .residentKey(ResidentKeyRequirement.REQUIRED)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build(),
            )
            .build(),
        )
        credential <- SoftAuthenticator.create(creationOptions.toCredentialsCreateJson, origin)
        registration <- ZIO.attempt(
          rp.finishRegistration(
            FinishRegistrationOptions
              .builder()
              .request(creationOptions)
              .response(PublicKeyCredential.parseRegistrationResponseJson(credential.responseJson.toJson))
              .build(),
          ),
        )
        _ <- ZIO.succeed(
          repository.put(
            RegisteredCredential
              .builder()
              .credentialId(registration.getKeyId.getId)
              .userHandle(userHandle(userId))
              .publicKeyCose(registration.getPublicKeyCose)
              .signatureCount(registration.getSignatureCount)
              .build(),
          ),
        )
        assertionRequest = rp.startAssertion(StartAssertionOptions.builder().userVerification(UserVerificationRequirement.REQUIRED).build())
        assertion <- SoftAuthenticator.get(credential, assertionRequest.toCredentialsGetJson, origin, userId)
        result <- ZIO.attempt(
          rp.finishAssertion(
            FinishAssertionOptions
              .builder()
              .request(assertionRequest)
              .response(PublicKeyCredential.parseAssertionResponseJson(assertion.toJson))
              .build(),
          ),
        )
      yield assertTrue(
        registration.getKeyId.getId.getBase64Url == credential.id,
        result.isSuccess,
        result.getCredential.getUserHandle == userHandle(userId),
      )
    },
    test("a malformed options document is a typed failure, not a dead fiber") {
      for
        credential <- SoftAuthenticator.create("""{"publicKey":{"challenge":"Y2hhbGxlbmdl","rp":{"id":"versola.test"}}}""", origin)
        creation <- SoftAuthenticator.create("""{"publicKey":{}}""", origin).either
        assertion <- SoftAuthenticator.get(credential, "not json at all", origin, userId).either
      yield assertTrue(
        creation.left.exists(isMalformed),
        assertion.left.exists(isMalformed),
      )
    },
  )
