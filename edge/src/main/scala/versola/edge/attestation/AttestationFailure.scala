package versola.edge.attestation

/** Why an attestation was not accepted. `kind` is what the metric is labelled with, so it is a closed set; `detail` is what an
  * operator or an SDK developer reads, and never carries a value the app sent. */
final case class AttestationFailure(kind: AttestationFailure.Kind, detail: String)

object AttestationFailure:

  enum Kind derives CanEqual:
    /** Nothing was sent. */
    case Absent

    /** Sent, but not in a form that parses. */
    case Malformed

    /** The certificate chain does not lead to the platform's root. */
    case UntrustedChain

    /** A certificate of the chain is on the platform's revocation list. */
    case Revoked

    /** The attestation was made for another request: its challenge, nonce or key does not match the one it arrived with. */
    case Mismatch

    /** The app is not one of the builds the client accepts. */
    case AppNotAccepted

    /** An App Attest key from the other environment, or one of a device that does not meet the bar (boot state, hardware). */
    case DeviceNotAccepted

    /** The key has not been attested to this edge. */
    case UnknownKey

    /** An assertion whose counter has not moved forward, or one that was already used. */
    case Replay

    /** The signature does not verify. */
    case BadSignature

    /** The client accepts no build of the platform the app sent. */
    case Unsupported

  def apply(kind: Kind, detail: String): AttestationFailure = new AttestationFailure(kind, detail)
