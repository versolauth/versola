package versola.auth.model

import versola.user.model.UserId
import zio.schema.*

import java.time.Instant

case class PasskeyRecord(
    id: CredentialId,
    userId: UserId,
    webauthnUserId: WebAuthnUserId,
    publicKey: Array[Byte],
    signatureCounter: Long,
    deviceType: CredentialDeviceType,
    backedUp: Boolean,
    backupEligible: Boolean,
    transports: List[AuthenticatorTransport],
    attestationObject: Option[Array[Byte]],
    clientDataJson: Option[Array[Byte]],
    createdAt: Instant,
    updatedAt: Instant,
)
