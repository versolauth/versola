package versola.central.configuration.clients

import versola.util.{Dpop, JsonWebKeySet, PrivateJsonWebKey}
import zio.json.ast.Json
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
    * RFC 8705 §2.2 `self_signed_tls_client_auth` is the one combination that is not two
    * credentials but one: it *is* the key set, matched against the presented certificate
    * rather than against an assertion's signature, so it needs `jwks` and refuses to be
    * registered without it -- a client with nothing to match against could never authenticate.
    *
    * The key set is validated here rather than at first use: a set that could never verify an
    * assertion is a registration mistake, and reporting it at registration costs an error
    * message, while reporting it at the token endpoint costs an `invalid_client` the operator
    * has to reverse-engineer.
    *
    * Which validation depends on what the keys are for. A §2.2 set is matched against a
    * certificate's public key rather than verified as a signature, so it is held only to
    * [[JsonWebKeySet.validateForCertificateMatching]] -- holding it to the assertion rules
    * would refuse a P-384 client whose certificate this server matches perfectly well.
    */
  def validateClientAuthentication(
      clientId: ClientId,
      mtlsAuth: Option[MutualTlsAuth],
      jwks: Option[JsonWebKeySet],
  ): Option[InvalidRegistrationConfiguration] =
    def invalid(reason: String) = Some(InvalidRegistrationConfiguration(clientId, reason))

    val combination = mtlsAuth match
      case Some(_: MutualTlsAuth.TlsClientAuth) if jwks.nonEmpty =>
        invalid("a client authenticates either with mtlsAuth or with jwks, not both")
      case Some(MutualTlsAuth.SelfSignedTlsClientAuth()) if jwks.isEmpty =>
        invalid("self_signed_tls_client_auth needs jwks - the registered keys are what a certificate is matched against")
      case _ =>
        None

    val validateKeys: Json.Obj => Either[String, JsonWebKeySet] = mtlsAuth match
      case Some(MutualTlsAuth.SelfSignedTlsClientAuth()) => JsonWebKeySet.validateForCertificateMatching
      case _ => JsonWebKeySet.validateForAssertions

    combination.orElse(
      jwks.flatMap(keySet => validateKeys(keySet.document).left.toOption)
        .map(reason => InvalidRegistrationConfiguration(clientId, s"jwks $reason")),
    )

  /** RFC 8705 §6.5 leaves it to the deployment to hand a terminated certificate to the
    * application, and this one does it per tenant: `auth` looks for a certificate only where
    * that tenant's `mtlsCertificateHeader` names one. A client registering `mtlsAuth` under a
    * tenant that names no header is a client that can never authenticate -- nothing will ever
    * look for the certificate its registration makes mandatory -- so it is refused here
    * rather than left to fail as an `invalid_client` at every token request.
    *
    * @param mtlsCertificateHeader the header of the client's own tenant, `None` both for a
    *                             tenant that configured none and for one with no settings
    *                             row at all: neither can produce a certificate.
    */
  def validateMtlsTermination(
      clientId: ClientId,
      mtlsAuth: Option[MutualTlsAuth],
      mtlsCertificateHeader: Option[String],
  ): Option[InvalidRegistrationConfiguration] =
    if mtlsAuth.nonEmpty && mtlsCertificateHeader.isEmpty then
      Some(InvalidRegistrationConfiguration(
        clientId,
        "mtlsAuth needs the client's tenant to set mtlsCertificateHeader - " +
          "without it no certificate ever reaches auth for this client",
      ))
    else
      None

  /** RFC 9101 §6.2: a request object is verified against the client's registered key set and
    * nothing else, so requiring one from a client that registered no keys registers a client
    * whose every authorization request is refused. Caught here rather than at `/authorize`,
    * where it would surface as a client that simply stopped working.
    */
  def validateRequestObjectRequirement(
      clientId: ClientId,
      requireSignedRequestObject: Boolean,
      jwks: Option[JsonWebKeySet],
  ): Option[InvalidRegistrationConfiguration] =
    if requireSignedRequestObject && jwks.isEmpty then
      Some(InvalidRegistrationConfiguration(
        clientId,
        "requireSignedRequestObject needs jwks - a request object is verified against no other keys",
      ))
    else
      None

  /** The key an edge signs as this client with has to be one auth can check the signature of,
    * and auth checks against `jwks` alone — so a signing key whose public half the client does
    * not publish configures an edge that fails every login it is asked to serve.
    *
    * `mtlsAuth` rules the key out whatever it is. Auth accepts an assertion only from a client
    * whose `mtlsAuth` is empty, because for an mTLS client the same `jwks` is what RFC 8705
    * §2.2 matches a certificate against; an edge holding a key for such a client presents no
    * certificate and is answered `invalid_client` at every `/par` and `/token` call.
    *
    * Caught at registration for the same reason [[validateRequestObjectRequirement]] is: the
    * alternative is a client that looks registered and is refused at `/token` or `/authorize`,
    * with nothing at either end naming the mismatch.
    */
  def validateEdgeSigningKey(
      clientId: ClientId,
      edgeSigningKey: Option[PrivateJsonWebKey],
      mtlsAuth: Option[MutualTlsAuth],
      jwks: Option[JsonWebKeySet],
  ): Option[InvalidRegistrationConfiguration] =
    edgeSigningKey.flatMap: key =>
      val problem = (mtlsAuth, jwks) match
        case (Some(_), _) =>
          Some(
            "cannot be combined with mtlsAuth - auth matches those keys against a certificate " +
              "(RFC 8705 §2.2) and refuses an assertion signed with them",
          )
        case (None, None) =>
          Some("needs jwks - what an edge signs with it is verified against no other keys")
        case (None, Some(keySet)) =>
          PrivateJsonWebKey.validate(key.document)
            .flatMap(validated => PrivateJsonWebKey.publishedIn(validated, keySet))
            .left.toOption
      problem.map(reason => InvalidRegistrationConfiguration(clientId, s"edgeSigningKey $reason"))

  /** RFC 9449 §5.1 proof key policy: a client may narrow what its own proofs are accepted
    * with, never widen it.
    *
    * `dpopMinRsaKeySize` is refused below [[Dpop.KeyPolicy.MinRsaKeySize]] rather than
    * silently raised to it, so that a registration reading `1024` is not stored as a number
    * the server ignores. Lowering the floor is what the field must never be able to do: a
    * short-modulus key is forgeable, and a `cnf.jkt` bound to one constrains nobody.
    *
    * The algorithm set is not checked against what the metadata document currently
    * advertises. That document is deployment state an operator can edit after the fact, so
    * a registration validated against it would be valid until someone changed it elsewhere;
    * the narrowing is applied as an intersection at proof time instead.
    */
  def validateDpopKeyPolicy(
      clientId: ClientId,
      dpopMinRsaKeySize: Option[Int],
  ): Option[InvalidRegistrationConfiguration] =
    dpopMinRsaKeySize
      .filter(_ < Dpop.KeyPolicy.MinRsaKeySize)
      .map(_ =>
        InvalidRegistrationConfiguration(
          clientId,
          s"dpopMinRsaKeySize must be at least ${Dpop.KeyPolicy.MinRsaKeySize} bits (RFC 7518 §3.3)",
        ),
      )

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
