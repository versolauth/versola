package versola.oauth.challenge.passkey

import com.yubico.internal.util.JacksonCodecs
import com.yubico.webauthn.data.{
  AuthenticatorSelectionCriteria,
  AuthenticatorTransport as YubicoTransport,
  ByteArray,
  PublicKeyCredential,
  PublicKeyCredentialCreationOptions,
  PublicKeyCredentialDescriptor,
  RelyingPartyIdentity,
  ResidentKeyRequirement,
  UserIdentity,
  UserVerificationRequirement,
}
import com.yubico.webauthn.{
  AssertionRequest,
  CredentialRepository,
  FinishAssertionOptions,
  FinishRegistrationOptions,
  RegisteredCredential,
  RelyingParty,
  StartAssertionOptions,
  StartRegistrationOptions,
}
import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyRecord}
import versola.oauth.client.model.PasskeySettings
import versola.user.model.UserId
import zio.{IO, Runtime, Unsafe, ZIO, ZLayer}

import java.nio.ByteBuffer
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The serialized state of an in-progress WebAuthn ceremony.
  *
  *   - `request` is the library-encoded ceremony state; persist it and pass it back to the
  *     matching `finish*` call.
  *   - `publicKeyOptions` is the JSON to hand to `navigator.credentials.{create,get}()`.
  */
case class PasskeyCeremony(request: String, publicKeyOptions: String)

/** The outcome of a successful assertion (login) ceremony. */
case class AssertionOutcome(userId: UserId, credentialId: CredentialId, signatureCount: Long)

sealed abstract class WebAuthnError(message: String) extends RuntimeException(message)

object WebAuthnError:
  case class CeremonyFailed(message: String) extends WebAuthnError(message)
  case object AssertionFailed extends WebAuthnError("assertion verification failed")
  case object CredentialNotFound extends WebAuthnError("credential not found on server")

trait WebAuthnService:
  /** Begin an enrollment ceremony for the given user. */
  def startRegistration(settings: PasskeySettings, userId: UserId, displayName: String): IO[WebAuthnError, PasskeyCeremony]

  /** Verify an enrollment response and persist the resulting passkey. */
  def finishRegistration(
      settings: PasskeySettings,
      userId: UserId,
      request: String,
      response: String,
      name: Option[String],
  ): IO[WebAuthnError, PasskeyRecord]

  /** Begin a passwordless (discoverable) assertion ceremony. */
  def startAssertion(settings: PasskeySettings): IO[WebAuthnError, PasskeyCeremony]

  /** Verify an assertion response, update usage, and resolve the authenticated user. */
  def finishAssertion(settings: PasskeySettings, request: String, response: String): IO[WebAuthnError, AssertionOutcome]

object WebAuthnService:
  def live: ZLayer[PasskeyRepository, Nothing, WebAuthnService] =
    ZLayer:
      for
        repository <- ZIO.service[PasskeyRepository]
        runtime    <- ZIO.runtime[Any]
      yield Impl(repository, runtime)

  private final class Impl(repository: PasskeyRepository, runtime: Runtime[Any]) extends WebAuthnService:

    private val mapper = JacksonCodecs.json()

    private def userIdToHandle(userId: UserId): ByteArray =
      val bb = ByteBuffer.allocate(16)
      bb.putLong(userId.getMostSignificantBits)
      bb.putLong(userId.getLeastSignificantBits)
      new ByteArray(bb.array())

    private def handleToUserId(handle: ByteArray): Either[String, UserId] =
      val b = handle.getBytes
      if b.length != 16 then Left("invalid userHandle length")
      else
        val bb = ByteBuffer.wrap(b)
        Right(UserId(new UUID(bb.getLong, bb.getLong)))

    // webauthn-server-core 2.7.0 only has USB, NFC, BLE, HYBRID, INTERNAL
    private def toYubico(t: AuthenticatorTransport): Option[YubicoTransport] = t match
      case AuthenticatorTransport.Ble      => Some(YubicoTransport.BLE)
      case AuthenticatorTransport.Hybrid   => Some(YubicoTransport.HYBRID)
      case AuthenticatorTransport.Internal => Some(YubicoTransport.INTERNAL)
      case AuthenticatorTransport.Nfc      => Some(YubicoTransport.NFC)
      case AuthenticatorTransport.Usb      => Some(YubicoTransport.USB)
      case _                               => None // Cable / SmartCard not in 2.7.0

    private def fromYubico(t: YubicoTransport): AuthenticatorTransport = t.getId match
      case "ble"      => AuthenticatorTransport.Ble
      case "hybrid"   => AuthenticatorTransport.Hybrid
      case "nfc"      => AuthenticatorTransport.Nfc
      case "usb"      => AuthenticatorTransport.Usb
      case _          => AuthenticatorTransport.Internal

    private def runSync[A](effect: zio.Task[A]): A =
      Unsafe.unsafe { unsafe ?=>
        runtime.unsafe.run(effect).getOrThrowFiberFailure()
      }

    private object CredRepo extends CredentialRepository:
      override def getCredentialIdsForUsername(username: String): java.util.Set[PublicKeyCredentialDescriptor] =
        UserId.parse(username).toOption match
          case None => java.util.Collections.emptySet()
          case Some(uid) =>
            runSync(repository.listByUser(uid)).map: rec =>
              PublicKeyCredentialDescriptor.builder()
                .id(new ByteArray(rec.id))
                .transports(rec.transports.flatMap(toYubico).toSet.asJava)
                .build()
            .toSet.asJava

      override def getUserHandleForUsername(username: String): java.util.Optional[ByteArray] =
        UserId.parse(username).toOption.map(userIdToHandle).toJava

      override def getUsernameForUserHandle(userHandle: ByteArray): java.util.Optional[String] =
        handleToUserId(userHandle).toOption.map(_.toString).toJava

      override def lookup(credentialId: ByteArray, userHandle: ByteArray): java.util.Optional[RegisteredCredential] =
        handleToUserId(userHandle).toOption.flatMap: uid =>
          runSync(repository.findByCredentialIdAndUser(CredentialId(credentialId.getBytes), uid)).map: rec =>
            RegisteredCredential.builder()
              .credentialId(new ByteArray(rec.id))
              .userHandle(userIdToHandle(rec.userId))
              .publicKeyCose(new ByteArray(rec.publicKey))
              .signatureCount(rec.signatureCounter)
              .build()
        .toJava

      override def lookupAll(credentialId: ByteArray): java.util.Set[RegisteredCredential] =
        runSync(repository.findByCredentialId(CredentialId(credentialId.getBytes))).map: rec =>
          RegisteredCredential.builder()
            .credentialId(new ByteArray(rec.id))
            .userHandle(userIdToHandle(rec.userId))
            .publicKeyCose(new ByteArray(rec.publicKey))
            .signatureCount(rec.signatureCounter)
            .build()
        .toSet.asJava

    private def buildRp(settings: PasskeySettings): RelyingParty =
      RelyingParty.builder()
        .identity(RelyingPartyIdentity.builder().id(settings.rpId).name(settings.rpName).build())
        .credentialRepository(CredRepo)
        .origins(settings.origins.toSet.asJava)
        .build()

    private def uvRequirement(uv: String): UserVerificationRequirement = uv.toLowerCase match
      case "required"    => UserVerificationRequirement.REQUIRED
      case "discouraged" => UserVerificationRequirement.DISCOURAGED
      case _             => UserVerificationRequirement.PREFERRED

    override def startRegistration(
        settings: PasskeySettings,
        userId: UserId,
        displayName: String,
    ): IO[WebAuthnError, PasskeyCeremony] =
      ZIO.attemptBlocking:
        val opts = buildRp(settings).startRegistration(
          StartRegistrationOptions.builder()
            .user(
              UserIdentity.builder()
                .name(userId.toString)
                .displayName(displayName)
                .id(userIdToHandle(userId))
                .build()
            )
            .authenticatorSelection(
              AuthenticatorSelectionCriteria.builder()
                .residentKey(ResidentKeyRequirement.REQUIRED)
                .userVerification(uvRequirement(settings.userVerification))
                .build()
            )
            .build()
        )
        PasskeyCeremony(
          request = mapper.writeValueAsString(opts),
          publicKeyOptions = opts.toCredentialsCreateJson,
        )
      .mapError(e => WebAuthnError.CeremonyFailed(e.getMessage))

    override def finishRegistration(
        settings: PasskeySettings,
        userId: UserId,
        request: String,
        response: String,
        name: Option[String],
    ): IO[WebAuthnError, PasskeyRecord] =
      for
        now    <- zio.Clock.instant
        record <- ZIO.attemptBlocking:
          val creationOptions = mapper.readValue(request, classOf[PublicKeyCredentialCreationOptions])
          val credential      = PublicKeyCredential.parseRegistrationResponseJson(response)
          val result = buildRp(settings).finishRegistration(
            FinishRegistrationOptions.builder().request(creationOptions).response(credential).build()
          )
          val transports = result.getKeyId.getTransports.toScala
            .map(_.asScala.toList.map(fromYubico))
            .getOrElse(Nil)
          PasskeyRecord(
            id = CredentialId(result.getKeyId.getId.getBytes),
            userId = userId,
            publicKey = result.getPublicKeyCose.getBytes,
            signatureCounter = result.getSignatureCount,
            deviceType = if result.isBackupEligible then CredentialDeviceType.MultiDevice else CredentialDeviceType.SingleDevice,
            backedUp = result.isBackedUp,
            backupEligible = result.isBackupEligible,
            transports = transports,
            attestationObject = Some(credential.getResponse.getAttestationObject.getBytes),
            clientDataJson = Some(credential.getResponse.getClientDataJSON.getBytes),
            aaguid = Some(result.getAaguid.getBytes),
            name = name,
            lastUsedAt = None,
            createdAt = now,
            updatedAt = now,
          )
        .mapError(e => WebAuthnError.CeremonyFailed(e.getMessage))
        _ <- repository.insert(record).mapError(e => WebAuthnError.CeremonyFailed(e.getMessage))
      yield record

    override def startAssertion(settings: PasskeySettings): IO[WebAuthnError, PasskeyCeremony] =
      ZIO.attemptBlocking:
        // Discoverable assertion is passwordless login that stands in for every primary factor, so the
        // passkey must itself be multi-factor: user verification is always required, ignoring the
        // configurable setting (which only relaxes UV for the post-auth enrollment ceremony).
        val assertionRequest = buildRp(settings).startAssertion(
          StartAssertionOptions.builder()
            .userVerification(UserVerificationRequirement.REQUIRED)
            .build()
        )
        PasskeyCeremony(
          request = mapper.writeValueAsString(assertionRequest),
          publicKeyOptions = assertionRequest.toCredentialsGetJson,
        )
      .mapError(e => WebAuthnError.CeremonyFailed(e.getMessage))

    override def finishAssertion(
        settings: PasskeySettings,
        request: String,
        response: String,
    ): IO[WebAuthnError, AssertionOutcome] =
      for
        now     <- zio.Clock.instant
        outcome <- ZIO.attemptBlocking:
          val assertionRequest = mapper.readValue(request, classOf[AssertionRequest])
          val credential       = PublicKeyCredential.parseAssertionResponseJson(response)
          if CredRepo.lookupAll(credential.getId).isEmpty then throw WebAuthnError.CredentialNotFound
          val result = buildRp(settings).finishAssertion(
            FinishAssertionOptions.builder().request(assertionRequest).response(credential).build()
          )
          if !result.isSuccess then throw WebAuthnError.AssertionFailed
          val userId = handleToUserId(result.getCredential.getUserHandle)
            .getOrElse(throw WebAuthnError.CeremonyFailed("invalid userHandle in assertion result"))
          AssertionOutcome(
            userId = userId,
            credentialId = CredentialId(result.getCredential.getCredentialId.getBytes),
            signatureCount = result.getSignatureCount,
          )
        .mapError:
          case e: WebAuthnError => e
          case e                => WebAuthnError.CeremonyFailed(e.getMessage)
        _ <- repository
          .updateUsage(outcome.credentialId, outcome.signatureCount, now)
          .mapError(e => WebAuthnError.CeremonyFailed(e.getMessage))
      yield outcome
