package versola.oauth.clientauth

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientCredentials, ClientIdWithAssertion, ClientIdWithSecret, OAuthClientRecord}
import versola.oauth.mtls.ClientCertificate
import versola.util.CoreConfig
import versola.util.http.Observability
import zio.*
import zio.http.Request

/** How a client proves who it is, shared by every endpoint that authenticates one: `/token`,
  * `/introspect`, `/revoke` and `/par`.
  *
  * Three methods reach here. A secret (RFC 6749 §2.3) is the baseline every confidential
  * client has. A client may additionally register exactly one stronger credential -- an mTLS
  * subject (RFC 8705 §2.1) or a JWK Set it signs assertions with (RFC 7523 §2.2) -- and
  * having registered one, that credential is the only thing that authenticates it: the secret
  * still exists but is no longer accepted, or registering the stronger method would weaken
  * the client to whichever of the two an attacker found easier.
  *
  * Each endpoint reports failure in its own error format, so the operations here fail with a
  * raw value — the unparseable header's reason, or nothing beyond "invalid" — leaving the
  * caller to `mapError`/`orElseFail` it into that endpoint's own error type.
  */
trait ClientAuthentication:
  /** Reads the client certificate the tenant's reverse proxy validated and forwarded, per the
    * header and encoding that tenant configured — RFC 8705 §6.5 leaves how an intermediary
    * passes a certificate it terminated mTLS for to the application entirely unspecified.
    *
    * The header is read only for a client the certificate can affect, which `relevance`
    * decides: authenticating by certificate at every endpoint, and additionally being bound to
    * one at `/token`. For anything else the header is irrelevant, and reading it anyway would
    * let a proxy misconfiguration fail requests that authenticate perfectly well by secret.
    *
    * A header that is present but unreadable fails the request rather than being treated as an
    * absent certificate: something did arrive for a client whose authentication depends on it,
    * and proceeding as though nothing had would fall back to a weaker credential than the
    * client's registration calls for. The failure carries the reason the certificate could not
    * be read, describing the deployment's proxy rather than the caller — the caller logs it
    * rather than returning it verbatim.
    */
  def certificate(
      request: Request,
      credentials: ClientCredentials,
      relevance: CertificateRelevance,
  ): IO[String, Option[ClientCertificate]]

  /** Authenticates the client named by the request's credentials.
    *
    * RFC 8705 §2.1: a client that registered `mtlsAuth` authenticates with its certificate,
    * and only with its certificate — the registered subject is the credential, so a secret
    * presented alongside one is not consulted, and a missing or non-matching certificate
    * fails. RFC 7523 §2.2 works the same way for a client that registered `jwks`: only an
    * assertion its keys verify authenticates it. Every other client authenticates by secret
    * as it always did.
    *
    * Fails with `()` for every way the caller could be at fault — there is only one failure
    * mode worth reporting, "not this client", and the caller already knows which error value
    * that becomes at its own endpoint. A `Throwable` is the replay guard being unreachable,
    * which is this server failing rather than the client, and is left for the caller to
    * surface as such.
    *
    * @param endpoint which endpoint is authenticating, which decides the `aud` an RFC 7523
    *                 assertion may name. Ignored by the other two methods.
    * @param secretRequired refuses a client that presents only its id. `verifySecret` treats a
    *                       bare `client_id` as authentication for a public client, which is
    *                       what the token endpoint's PKCE exchange needs; at an endpoint that
    *                       reads or revokes tokens it would let anyone knowing a public id act
    *                       for that client. A certificate or an assertion still authenticates
    *                       one — the point is that no credential at all does not.
    */
  def authenticate(
      credentials: ClientCredentials,
      certificate: Option[ClientCertificate],
      endpoint: AuthenticatedEndpoint,
      secretRequired: Boolean = false,
  ): IO[Throwable | Unit, OAuthClientRecord]

/** Why an endpoint asks [[ClientAuthentication.certificate]] to read the header at all -- a
  * closed set of reasons rather than an arbitrary `OAuthClientRecord => Boolean`, so a call
  * site states its reason and [[appliesTo]] is the one place that reason is turned into a
  * check on the client's registration.
  */
enum CertificateRelevance:
  /** RFC 8705 §2.1: the certificate is how this client authenticates, checked at every
    * endpoint that authenticates a client -- `/token`, `/introspect`, `/revoke`, `/par`. */
  case Authentication
  /** RFC 8705 §3: the certificate additionally binds the tokens `/token` issues to a client
    * that authenticates by secret and asked for that binding, so the header is read there for
    * a wider set of clients than the ones [[Authentication]] alone would cover. */
  case TokenIssuance

  def appliesTo(client: OAuthClientRecord): Boolean = this match
    case CertificateRelevance.Authentication => client.mtlsAuth.nonEmpty
    case CertificateRelevance.TokenIssuance  => client.bindsAccessTokens

/** The endpoints that authenticate a client, and the path each is served at.
  *
  * RFC 7523 §3 requires an assertion's `aud` to name the server it is sent to. OpenID Connect
  * Core §9 reads that as the endpoint's own URL while the OAuth security BCP reads it as the
  * issuer identifier, and clients in the wild send either -- so both are accepted, and this
  * is what supplies the endpoint half.
  */
enum AuthenticatedEndpoint(val path: String):
  case Token extends AuthenticatedEndpoint("/token")
  case PushedAuthorizationRequest extends AuthenticatedEndpoint("/par")
  case Introspection extends AuthenticatedEndpoint("/introspect")
  case Revocation extends AuthenticatedEndpoint("/revoke")

  def acceptedAudiences(issuer: String): Set[String] =
    val base = issuer.stripSuffix("/")
    Set(issuer, base, base + path)

object ClientAuthentication:
  def live: ZLayer[OAuthConfigurationService & ClientAssertionService & CoreConfig, Nothing, ClientAuthentication] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      oauthClientService: OAuthConfigurationService,
      clientAssertionService: ClientAssertionService,
      config: CoreConfig,
  ) extends ClientAuthentication:

    override def certificate(
        request: Request,
        credentials: ClientCredentials,
        relevance: CertificateRelevance,
    ): IO[String, Option[ClientCertificate]] =
      val clientId = credentials.clientId

      oauthClientService.find(clientId).flatMap:
        case Some(client) if relevance.appliesTo(client) =>
          oauthClientService.getMtlsCertificateSource(clientId).flatMap: source =>
            source.flatMap(s => request.headers.get(s.header).map(_ -> s.encoding)) match
              case None =>
                ZIO.none
              case Some((headerValue, encoding)) =>
                ZIO.fromEither(ClientCertificate.parse(headerValue, encoding))
                  .tapError(reason =>
                    ZIO.logWarning(s"Couldn't parse the client certificate of $clientId: $reason"),
                  )
                  .asSome
        case _ =>
          ZIO.none

    override def authenticate(
        credentials: ClientCredentials,
        certificate: Option[ClientCertificate],
        endpoint: AuthenticatedEndpoint,
        secretRequired: Boolean = false,
    ): IO[Throwable | Unit, OAuthClientRecord] =
      Observability.setClientId(credentials.clientId) *> (credentials match
        case ClientIdWithAssertion(clientId, assertion) =>
          oauthClientService.find(clientId).flatMap:
            // A client that registered no keys cannot be authenticated this way, whatever the
            // assertion says -- including a client that authenticates by secret, which must
            // not become assertion-authenticable just by being sent one.
            case Some(client) if client.jwks.nonEmpty =>
              clientAssertionService
                .verify(client, assertion, endpoint.acceptedAudiences(config.jwt.issuer))
                .mapError {
                  case error: Throwable => error
                  case _: ClientAssertionService.Error => ()
                }
                .as(client)
            case _ =>
              ZIO.fail(())

        case ClientIdWithSecret(clientId, clientSecret) =>
          oauthClientService.find(clientId).flatMap:
            case Some(client) if client.mtlsAuth.nonEmpty =>
              ZIO.succeed(client).filterOrFail(
                _.mtlsAuth.exists(auth => certificate.exists(_.matches(auth))),
              )(())
            // The client registered a key set, so an assertion is its credential and the
            // secret it also holds is not one. Accepting the secret here would leave
            // `private_key_jwt` as an option an attacker can simply decline to use.
            case Some(client) if client.jwks.nonEmpty =>
              ZIO.fail(())
            case _ if secretRequired && clientSecret.isEmpty =>
              ZIO.fail(())
            case _ =>
              oauthClientService.verifySecret(clientId, clientSecret)
                .someOrFail(())
      )
