package versola.oauth.mtls

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientCredentials, ClientIdWithSecret, OAuthClientRecord}
import versola.util.http.Observability
import zio.*
import zio.http.Request

/** What RFC 8705 §2 changes about authenticating a client, shared by every endpoint that
  * authenticates one: `/token`, `/introspect`, `/revoke` and `/par`.
  *
  * Each of those reports failure in its own error format, so both operations here fail with a
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
    * presented alongside one is not consulted, and a missing or non-matching certificate fails.
    * Every other client authenticates as it always did, whether or not a certificate came with
    * the request.
    *
    * Fails with `()` — there is only one failure mode, "not this client", and the caller
    * already knows which error value that becomes at its own endpoint.
    *
    * @param secretRequired refuses a client that presents only its id. `verifySecret` treats a
    *                       bare `client_id` as authentication for a public client, which is
    *                       what the token endpoint's PKCE exchange needs; at an endpoint that
    *                       reads or revokes tokens it would let anyone knowing a public id act
    *                       for that client. A certificate still authenticates one — the point
    *                       is that no credential at all does not.
    */
  def authenticate(
      credentials: ClientCredentials,
      certificate: Option[ClientCertificate],
      secretRequired: Boolean = false,
  ): IO[Unit, OAuthClientRecord]

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

object ClientAuthentication:
  def live: ZLayer[OAuthConfigurationService, Nothing, ClientAuthentication] =
    ZLayer.fromFunction(Impl(_))

  class Impl(oauthClientService: OAuthConfigurationService) extends ClientAuthentication:

    override def certificate(
        request: Request,
        credentials: ClientCredentials,
        relevance: CertificateRelevance,
    ): IO[String, Option[ClientCertificate]] =
      val clientId = credentials match
        case ClientIdWithSecret(clientId, _) => clientId

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
        secretRequired: Boolean = false,
    ): IO[Unit, OAuthClientRecord] =
      credentials match
        case ClientIdWithSecret(clientId, clientSecret) =>
          Observability.setClientId(clientId) *>
            oauthClientService.find(clientId).flatMap:
              case Some(client) if client.mtlsAuth.nonEmpty =>
                ZIO.succeed(client).filterOrFail(
                  _.mtlsAuth.exists(auth => certificate.exists(_.matches(auth))),
                )(())
              case _ if secretRequired && clientSecret.isEmpty =>
                ZIO.fail(())
              case _ =>
                oauthClientService.verifySecret(clientId, clientSecret)
                  .someOrFail(())
