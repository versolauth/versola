package versola.edge.attestation

import zio.json.JsonCodec

/** What this edge does with the attestation an app sends on `/native/start` (#423), as central syncs it for one client.
  * Central's `ClientAttestation` is the other end of this wire form; the two are separate types because edge does not depend on
  * central, and a field central adds that this edge does not know is ignored on decoding. */
enum AttestationMode derives JsonCodec, CanEqual:
  /** Nothing is asked and nothing is checked. */
  case off

  /** Verified and counted, never refused: an operator sees what `enforce` would turn away before it does. */
  case report

  /** `/native/start` without a verifiable attestation of an accepted build is refused. */
  case enforce

/** Which Apple environment an iOS app's App Attest keys come from. */
enum IosEnvironment derives JsonCodec, CanEqual:
  case production, development

/** The iOS builds accepted, by App ID (`<Team ID>.<bundle id>`). */
case class IosApps(
    appIds: Set[String],
    environment: IosEnvironment,
) derives JsonCodec

/** An Android build accepted: its package and the SHA-256 (lower-case hex) of the certificates it may be signed with.
  *
  * @param playIntegrity whether a Play Integrity verdict is required besides the key attestation: the Play Store's own statement that
  *   the install is the genuine, Play-recognised build on a genuine device. A policy written before this existed decodes as false. */
case class AndroidApp(
    packageName: String,
    certificateDigests: Set[String],
    playIntegrity: Boolean = false,
) derives JsonCodec

case class AttestationPolicy(
    mode: AttestationMode,
    ios: Option[IosApps],
    android: List[AndroidApp],
) derives JsonCodec

object AttestationPolicy:
  /** What a client with no policy has. */
  val off: AttestationPolicy = AttestationPolicy(mode = AttestationMode.off, ios = None, android = Nil)
