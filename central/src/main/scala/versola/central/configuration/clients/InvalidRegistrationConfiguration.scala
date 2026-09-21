package versola.central.configuration.clients

import versola.util.JsonWebKeySet
import zio.{Duration, duration2DurationOps}

/** Raised when a client's `registrationFlow` cannot be satisfied by its `authFlow`,
  * for example when registration is enabled without an auth flow or for a
  * login+password flow, which has no credential the user can prove ownership of.
  */
case class InvalidRegistrationConfiguration(clientId: ClientId, reason: String)

object InvalidRegistrationConfiguration:
  /** A DPoP-bound access token is inert without the private key, so a stolen copy cannot be
    * replayed and it can be long-lived - the floor exists only to keep the setting deliberate
    * rather than an accidental leftover from an unbound default. */
  val MinDpopBoundAccessTokenTtl: Duration = Duration.fromSeconds(3600)

  /** Applies to every access token regardless of binding: it bounds how stale a deny-list
    * entry or a revoked permission grant can get before it expires on its own.
    */
  val MaxAccessTokenTtl: Duration = Duration.fromSeconds(86400)

  def validateAccessTokenTtl(
      clientId: ClientId,
      accessTokenTtl: Duration,
      dpopBoundAccessTokens: Boolean,
  ): Option[InvalidRegistrationConfiguration] =
    if accessTokenTtl > MaxAccessTokenTtl then
      Some(InvalidRegistrationConfiguration(clientId, s"accessTokenTtl must not exceed ${MaxAccessTokenTtl.toSeconds}s"))
    else if dpopBoundAccessTokens && accessTokenTtl < MinDpopBoundAccessTokenTtl then
      Some(InvalidRegistrationConfiguration(
        clientId,
        s"accessTokenTtl must be at least ${MinDpopBoundAccessTokenTtl.toSeconds}s when dpopBoundAccessTokens is enabled",
      ))
    else
      None

  /** A client authenticates one way. RFC 8705 §2.1 `tls_client_auth` and RFC 7523 §2.2
    * `private_key_jwt` are each a credential that replaces the secret at the token endpoint,
    * so registering both does not make a client harder to impersonate -- it gives an attacker
    * two independent ways to do it, and leaves the operator reading one of them believing it
    * is the one in force.
    *
    * The key set is validated here rather than at first use: a set that could never verify an
    * assertion is a registration mistake, and reporting it at registration costs an error
    * message, while reporting it at the token endpoint costs an `invalid_client` the operator
    * has to reverse-engineer.
    */
  def validateClientAuthentication(
      clientId: ClientId,
      mtlsAuth: Option[MutualTlsAuth],
      jwks: Option[JsonWebKeySet],
  ): Option[InvalidRegistrationConfiguration] =
    if mtlsAuth.nonEmpty && jwks.nonEmpty then
      Some(InvalidRegistrationConfiguration(
        clientId,
        "a client authenticates either with mtlsAuth or with jwks, not both",
      ))
    else
      jwks.flatMap(keySet => JsonWebKeySet.validate(keySet.document).left.toOption)
        .map(reason => InvalidRegistrationConfiguration(clientId, s"jwks $reason"))

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
