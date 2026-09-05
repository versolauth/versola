package versola.central.configuration.clients

/** Raised when a consent-screen URI is not a safe absolute HTTPS URL. */
case class InvalidConsentUri(field: String, reason: String)
    extends IllegalArgumentException(s"Invalid $field: $reason")