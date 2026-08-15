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
        case Some(auth) if auth.primary.credentials.sizeIs != 1 =>
          invalid("registration requires exactly one primary credential, either phone or email")
        case Some(auth) if auth.primary.credentials.headOption.map(_.toString) != Some(flow.credential.toString) =>
          invalid("registration credential must match the client's primary credential")
        case Some(auth) if auth.primary.inlinePassword =>
          invalid("registration is not available when the credential card asks for a password inline")
        case Some(_) if flow.roleIds.isEmpty =>
          invalid("registration requires at least one assigned role")
        case Some(_) if flow.steps.isEmpty =>
          invalid("registration requires at least one step")
        case Some(_) if !flow.steps.headOption.contains(RegistrationStep.Otp()) =>
          invalid("registration must start with OTP verification")
        case Some(_) if flow.steps.distinct.size != flow.steps.size =>
          invalid("registration steps must be distinct")
        case Some(_)
            if flow.steps.contains(RegistrationStep.SetPassword()) &&
              flow.steps.contains(RegistrationStep.PasskeyEnroll()) =>
          invalid("registration allows only one of set-password or passkey enrollment")
        case Some(_) =>
          None
