package versola.auth.model

import versola.user.model.UserId

import java.time.Instant

case class PasskeyRecord(
    id: CredentialId,
    userId: UserId,
    publicKey: Array[Byte],
    signatureCounter: Long,
    deviceType: CredentialDeviceType,
    backedUp: Boolean,
    backupEligible: Boolean,
    transports: List[AuthenticatorTransport],
    attestationObject: Option[Array[Byte]],
    clientDataJson: Option[Array[Byte]],
    aaguid: Option[Array[Byte]],
    name: Option[String],
    lastUsedAt: Option[Instant],
    createdAt: Instant,
    updatedAt: Instant,
)
