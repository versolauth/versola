package versola.central.configuration.clients

/** Raised when a client's `registrationFlow` cannot be satisfied by its `authFlow`,
  * for example when registration is enabled without an auth flow or for a
  * login+password flow, which has no credential the user can prove ownership of.
  */
case class InvalidRegistrationConfiguration(clientId: ClientId, reason: String)

object InvalidRegistrationConfiguration:
  /** Registration is only reachable from a credential card that asks for a phone or an
    * email, since account creation requires proving ownership of the entry credential.
    */
  def validate(
      clientId: ClientId,
      authFlow: Option[AuthFlow],
      registrationFlow: Option[RegistrationFlow],
  ): Option[InvalidRegistrationConfiguration] =
    registrationFlow.flatMap: flow =>
      def invalid(reason: String) = Some(InvalidRegistrationConfiguration(clientId, reason))
      authFlow match
        case None =>
          invalid("registration requires an authentication flow")

        case Some(auth) if auth.primary.credentials.contains(PrimaryCredential.login) =>
          invalid("registration is not available for login+password flows")

        // A card offering several credentials renders one combined field, leaving the entry
        // credential ambiguous, so registration needs exactly one of phone or email.
        case Some(auth) if auth.primary.credentials.sizeIs != 1 =>
          invalid("registration requires exactly one primary credential, either phone or email")

        // The register button shares the credential card's form, so an inline password field
        // would be required to register while the registration flow ignores it.
        case Some(auth) if auth.primary.inlinePassword =>
          invalid("registration is not available when the credential card asks for a password inline")

        case Some(_) if flow.steps.isEmpty =>
          invalid("registration requires at least one step")

        case Some(_) if flow.steps.distinct.size != flow.steps.size =>
          invalid("registration steps must be distinct")

        case Some(_)
            if flow.steps.contains(RegistrationStep.SetPassword()) &&
              flow.steps.contains(RegistrationStep.PasskeyEnroll()) =>
          invalid("registration allows only one of set-password or passkey enrollment")

        case Some(_) =>
          None
