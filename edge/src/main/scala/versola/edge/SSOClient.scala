package versola.edge

import versola.edge.model.{AccessToken, AuthorizationPreset, ClientCredential, ClientId, Code, CodeVerifier, OAuthClient, RefreshToken, State, TokenResponse}
import versola.util.{Base64, ClientAssertion, EdgeAssertion, PrivateClientCertificate, RedirectUri, RequestObject, Secret}
import zio.Chunk
import zio.http.*
import zio.json.ast.Json
import zio.json.{JsonCodec, jsonField}
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{IO, Task, UIO, URLayer, ZIO, ZLayer}

trait SSOClient:
  /** Where the browser is sent to start a login.
    *
    * Effectful, and not only because of the network: what the URL carries depends on what the
    * client registered. A client that requires a signed request object (RFC 9101) gets its
    * request signed with that client's key, and one that requires pushed authorization
    * requests (RFC 9126) has the request POSTed to `/par` first, leaving the browser a
    * `request_uri` to carry instead of the request itself.
    */
  def authorizeUri(
      preset: AuthorizationPreset,
      client: OAuthClient,
      codeChallenge: String,
      state: State,
      overrideParams: Map[String, String] = Map.empty,
  ): Task[URL]

  def exchangeAuthorizationCode(
      code: Code,
      codeVerifier: CodeVerifier,
      redirectUri: RedirectUri,
      clientId: ClientId,
      credential: ClientCredential,
  ): Task[TokenResponse]

  def exchangeRefreshToken(
      refreshToken: RefreshToken,
      clientId: ClientId,
      credential: ClientCredential,
  ): IO[Throwable | SSOClient.InvalidGrant.type, TokenResponse]

  /** `binding` names what the caller already knows about `accessToken` -- what its `cnf` claim
    * binds it to -- so this brings only what that binding obliges it to, and nothing at all
    * for the common case of a plain bearer token, which auth accepts over `Bearer` as it is. */
  def userInfo(
      accessToken: AccessToken,
      binding: SSOClient.TokenBinding,
  ): IO[Throwable | SSOClient.UserInfoUnauthorized.type, Json.Obj]

object SSOClient:
  case object InvalidGrant
  case object UserInfoUnauthorized

  /** What a token's `cnf` claim obliges a call made with it to bring along. Auth enforces the
    * binding on `/userinfo` too, and the two bindings are honoured in different places -- a
    * key by an assertion in a header, a certificate by the handshake itself -- so the caller
    * names which one it holds rather than this guessing from the token.
    */
  enum TokenBinding:
    /** A plain bearer token: auth asks nothing of the call beyond the token. */
    case Unbound

    /** RFC 9449 §6.1 `cnf.jkt`. */
    case Key

    /** RFC 8705 §3.1 `cnf.x5t#S256`: the token is honoured only over the certificate it names,
      * which is the one this client authenticates with. */
    case Certificate(clientId: ClientId, credential: ClientCredential)

  /** A token bound to a certificate this edge does not hold -- central registered the client
    * for some other credential after the token was issued. Raised rather than calling without
    * it and reading back a bare refusal that names nothing about the certificate that was
    * missing.
    */
  case class TokenBoundToAbsentCertificate(clientId: ClientId)
    extends RuntimeException(
      s"the access token is bound to a client certificate (RFC 8705 §3.1), but this edge holds " +
        s"no certificate for client '$clientId' -- central sent some other credential",
    )

  /** A client whose registration demands something the credential central sent cannot
    * produce -- a signed request object from a client edge holds only a secret for. Raised
    * rather than quietly sending the plain request auth is registered to refuse, which would
    * surface as an `invalid_request` with nothing at either end naming the cause.
    */
  case class CredentialCannotSign(clientId: ClientId)
    extends RuntimeException(
      s"client '$clientId' requires a signed request object, but this edge holds no signing " +
        "key for it -- central sent only a client secret",
    )

  /** A certificate can only be presented in a handshake there is one of. Raised rather than
    * calling anyway, which would send the request unauthenticated over plaintext and read back
    * an `invalid_client` that names the certificate nothing asked for.
    */
  case class CredentialNeedsTls(clientId: ClientId, endpoint: URL)
    extends RuntimeException(
      s"client '$clientId' authenticates with a certificate, which cannot be presented to " +
        s"'${endpoint.encode}' -- it is reached over plaintext, so nothing terminates TLS " +
        "for auth to read a certificate from",
    )

  /** A handshake whose far side is not authenticated is one any host that can intercept the
    * route may complete. Raised rather than presenting the certificate to it: zio-http's
    * `ClientSSLConfig.Default` is Netty's `InsecureTrustManagerFactory`, which accepts every
    * server certificate, so falling back to it would carry an authenticated session -- and
    * the tokens it returns -- over a connection to whatever answered.
    */
  case class CredentialNeedsTrustedServer(clientId: ClientId, endpoint: URL)
    extends RuntimeException(
      s"client '$clientId' authenticates with a certificate, which cannot be presented to " +
        s"'${endpoint.encode}' -- no trust anchors are configured for it, so the server at " +
        "that address would not be authenticated. Set `versola-internal-trusted-certificates`",
    )

  /** RFC 9126 §2.2: what `/par` hands back in place of the request. */
  private case class PushedAuthorizationResponse(
      @jsonField("request_uri") requestUri: String,
  ) derives JsonCodec

  private case class ErrorResponse(
      error: String,
      @jsonField("error_description") errorDescription: Option[String] = None,
  ) derives JsonCodec

  val live: URLayer[Client & EdgeConfig & ClientCertificateFiles, SSOClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      certificateFiles: ClientCertificateFiles,
  ) extends SSOClient:
    // authorizeUrl is where the browser gets redirected -- it must stay on
    // versolaUrl (public-facing), never internalUrl. tokenUrl and
    // userInfoUrl are real calls this process makes itself below, so they
    // use internalUrl -- see the comments on EdgeConfig for why these two
    // aren't the same address everywhere.
    private val authorizeUrl: URL = config.versolaUrl / "authorize"
    private val tokenUrl: URL = config.internalUrl / "token"
    private val pushedAuthorizationUrl: URL = config.internalUrl / "par"
    private val userInfoUrl = config.internalUrl / "userinfo"

    /** What auth accepts as the `aud` of an assertion or a request object. Both take the
      * issuer identifier (`ClientAssertionService`, `RequestObjectService`), and the issuer is
      * the public address -- `internalUrl` is how this process reaches auth, not what auth
      * calls itself, and the two differ wherever edge and auth sit on separate networks.
      */
    private val audience: String = config.versolaUrl.encode

    /** How the certificate auth's internal endpoint presents is validated, for the calls that
      * authenticate in the handshake. Only those: every other call goes over the client's own
      * configuration, which this never touches.
      *
      * Absent is not a default to fall back on but a refusal (`CredentialNeedsTrustedServer`):
      * the only untrusted option zio-http offers is one that authenticates no server at all.
      */
    private val internalTrust: Option[ClientSSLConfig] =
      config.versolaInternalTrustedCertificates.map(ClientSSLConfig.FromCertFile.apply)

    override def authorizeUri(
        preset: AuthorizationPreset,
        client: OAuthClient,
        codeChallenge: String,
        state: State,
        overrideParams: Map[String, String] = Map.empty,
    ): Task[URL] =
      val params = List(
        "client_id" -> preset.clientId,
        "redirect_uri" -> preset.redirectUri,
        "response_type" -> preset.responseType,
        "code_challenge" -> codeChallenge,
        "code_challenge_method" -> "S256",
        "state" -> state,
      ) ++ Option.when(preset.scope.nonEmpty)("scope" -> preset.scope.mkString(" ")).toList
        ++ preset.uiLocales
          .filterNot(_ => overrideParams.contains("ui_locales"))
          .map(locales => "ui_locales" -> locales.mkString(" "))
        ++ preset.customParameters
          .filterNot { case (key, _) => overrideParams.contains(key) }
          .flatMap { case (key, values) => values.map(value => key -> value) }
        ++ overrideParams.toList

      for
        // Signing first: a pushed request carrying a request object is what RFC 9126 §3 asks
        // of a client registered for both, and signing after pushing would leave the object
        // stating a request auth has already stored under a different one.
        signed <- signRequestObject(params, client)
        url <-
          if client.requirePushedAuthorizationRequests then push(signed, client)
          else ZIO.succeed(authorizeUrl.addQueryParams(signed))
      yield url

    /** RFC 9101 §4: the request as a JWT the client signed, sent as the `request` parameter
      * alongside the `client_id` §6.3 requires to match the object's own claim.
      *
      * Left alone for a client that did not register the requirement. A request object is not
      * free -- it is verified against the client's key set on every authorization request --
      * and sending one unasked would hold clients to a check they never registered for.
      */
    private def signRequestObject(
        params: List[(String, String)],
        client: OAuthClient,
    ): Task[List[(String, String)]] =
      if !client.requireSignedRequestObject then ZIO.succeed(params)
      else
        for
          signing <- ZIO.fromOption(client.credential.signingKey)
            .orElseFail(SSOClient.CredentialCannotSign(client.id))
          token <- RequestObject.sign(
            parameters = params.groupMap(_._1)(_._2).view.mapValues(Chunk.fromIterable).toMap,
            clientId = client.id,
            audience = audience,
            algorithm = signing.algorithm,
            keyId = signing.keyId,
            privateKey = signing.privateKey,
          )
        yield List("client_id" -> client.id, RequestObject.Parameter -> token)

    /** RFC 9126 §2: the request is POSTed to `/par` over an authenticated back channel, and
      * the browser is sent to `/authorize` with only the `request_uri` it hands back.
      *
      * What reaches the user agent is then a single-use reference the client is bound to,
      * rather than a request a user agent could have rewritten on the way -- which is the
      * whole of what §6.2's requirement buys, and why the request itself must not also be
      * appended to the redirect.
      */
    private def push(params: List[(String, String)], client: OAuthClient): Task[URL] =
      for
        authenticated <- authenticate(
          Form(params.map(FormField.simpleField(_, _))*),
          client.id,
          client.credential,
          pushedAuthorizationUrl,
        )
        request = Request
          .post(pushedAuthorizationUrl, Body.fromURLEncodedForm(authenticated.form))
          .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
          .addHeaders(authenticated.headers)
        response <- ZIO.scoped(authenticated.over(httpClient).request(request))
        pushed <-
          if response.status.isSuccess then response.bodyAs[SSOClient.PushedAuthorizationResponse]
          else
            response.bodyAs[ErrorResponse].flatMap: error =>
              ZIO.fail(new RuntimeException(
                s"Pushed authorization request failed: ${response.status.code} ${error.error}" +
                  error.errorDescription.fold("")(d => s" - $d"),
              ))
      yield authorizeUrl.addQueryParams(List(
        "client_id" -> client.id,
        "request_uri" -> pushed.requestUri,
      ))

    /** A form, the headers and the connection that authenticate a call as `clientId`.
      *
      * The three methods land in three different places -- RFC 6749 §2.3.1 puts a secret in
      * the `Authorization` header, RFC 7523 §2.2 puts an assertion in the body, and RFC 8705
      * §2 puts a certificate in the handshake, which is not in the request at all -- so this
      * hands back all of them rather than one, and every authenticated call goes through it
      * instead of each deciding for itself.
      */
    private def authenticate(
        form: Form,
        clientId: ClientId,
        credential: ClientCredential,
        endpoint: URL,
    ): Task[Authenticated] =
      credential match
        case ClientCredential.ClientSecret(secret) =>
          ZIO.succeed(Authenticated(
            form,
            Headers(Header.Authorization.Basic(clientId, Base64.urlEncode(secret))),
          ))

        case ClientCredential.PrivateKeyJwt(signing) =>
          ClientAssertion.issue(
            clientId = clientId,
            audience = audience,
            algorithm = signing.algorithm,
            keyId = signing.keyId,
            privateKey = signing.privateKey,
          ).map: assertion =>
            // RFC 7521 §4.2 lets the assertion stand for `client_id`, but auth reads the
            // parameter too and a request that omits it is harder to trace at either end.
            // Only where the caller has not already named the client, though: RFC 6749 §3.1
            // allows a parameter once, and a pushed request carries the `client_id` RFC 9101
            // §6.3 requires beside the request object -- a second copy decodes on the far
            // side as one comma-joined value naming no client at all.
            val identified =
              if form.get("client_id").isDefined then form
              else form.append(FormField.simpleField("client_id", clientId))

            Authenticated(
              identified
                .append(FormField.simpleField("client_assertion_type", ClientAssertion.Type))
                .append(FormField.simpleField("client_assertion", assertion)),
              Headers.empty,
            )

        case ClientCredential.MutualTls(certificate) =>
          mutualTlsConnection(clientId, certificate, endpoint).map: connection =>
            // RFC 8705 §2: the certificate is the whole credential and the request carries
            // none of it. `client_id` still has to name the client -- §2.1 requires it, the
            // certificate being matched against what that client registered -- unless the
            // caller has already named it, on the same terms as the assertion above.
            val identified =
              if form.get("client_id").isDefined then form
              else form.append(FormField.simpleField("client_id", clientId))

            Authenticated(identified, Headers.empty, Some(connection))

    /** The connection a certificate is presented over: the certificate itself, and the anchors
      * the server answering the far side is checked against. Shared by the calls that
      * authenticate with it and by the one that only has to prove a token's binding
      * (`userInfo`) -- both hand the same certificate to the same server, and both are refused
      * on the same terms if the connection cannot carry it safely.
      */
    private def mutualTlsConnection(
        clientId: ClientId,
        certificate: PrivateClientCertificate.Material,
        endpoint: URL,
    ): Task[ClientSSLConfig] =
      for
        _ <- ZIO.fail(SSOClient.CredentialNeedsTls(clientId, endpoint))
          .unless(endpoint.scheme.contains(Scheme.HTTPS))
        trust <- ZIO.fromOption(internalTrust)
          .orElseFail(SSOClient.CredentialNeedsTrustedServer(clientId, endpoint))
        certificateConfig <- certificateFiles.present(certificate)
      yield ClientSSLConfig.FromClientAndServerCert(trust, certificateConfig)

    /** @param ssl how the connection this is sent over authenticates, for the one method that
      *            authenticates there rather than in the request. `None` leaves the client's
      *            own configuration alone -- every other call this edge makes shares it. */
    private case class Authenticated(form: Form, headers: Headers, ssl: Option[ClientSSLConfig] = None):
      def over(client: Client): Client = ssl.fold(client)(client.ssl)

    override def exchangeAuthorizationCode(
        code: Code,
        codeVerifier: CodeVerifier,
        redirectUri: RedirectUri,
        clientId: ClientId,
        credential: ClientCredential,
    ): Task[TokenResponse] =
      val form = Form(
        FormField.simpleField("grant_type", "authorization_code"),
        FormField.simpleField("code", code),
        FormField.simpleField("redirect_uri", redirectUri),
        FormField.simpleField("code_verifier", codeVerifier),
      )

      for
        authenticated <- authenticate(form, clientId, credential, tokenUrl)
        request = Request
          .post(tokenUrl, Body.fromURLEncodedForm(authenticated.form))
          .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
          .addHeaders(authenticated.headers)
        response <- ZIO.scoped(authenticated.over(httpClient).request(request))
        tokenResponse <-
          if response.status.isSuccess then response.bodyAs[TokenResponse]
          else
            response.bodyAs[ErrorResponse].flatMap: error =>
              ZIO.fail(new RuntimeException(s"Authorization code exchange failed: ${response.status.code} ${error.error}${error.errorDescription.fold("")(d => s" - $d")}"))
      yield tokenResponse

    override def exchangeRefreshToken(
        refreshToken: RefreshToken,
        clientId: ClientId,
        credential: ClientCredential,
    ): IO[Throwable | InvalidGrant.type, TokenResponse] =
      val form = Form(
        FormField.simpleField("grant_type", "refresh_token"),
        FormField.simpleField("refresh_token", refreshToken),
      )

      for
        authenticated <- authenticate(form, clientId, credential, tokenUrl)
        request = Request
          .post(tokenUrl, Body.fromURLEncodedForm(authenticated.form))
          .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
          .addHeaders(authenticated.headers)
        response <- ZIO.scoped(authenticated.over(httpClient).request(request))
        result <-
          if response.status.isSuccess then response.bodyAs[TokenResponse]
          else
            response.bodyAs[ErrorResponse].flatMap: error =>
              if error.error == "invalid_grant" then ZIO.fail(InvalidGrant)
              else ZIO.fail(new RuntimeException(s"Token exchange failed: ${response.status.code} ${error.error}"))
      yield result

    override def userInfo(
        accessToken: AccessToken,
        binding: SSOClient.TokenBinding,
    ): IO[Throwable | SSOClient.UserInfoUnauthorized.type, Json.Obj] =
      for
        // auth enforces RFC 9449 §7 on this token too, and this call carries no proof of its
        // own: the client's key signed the one edge already checked, and edge does not hold
        // that key to mint another. The assertion is how auth tells this call apart from the
        // `Bearer` downgrade of a key-bound token -- minted only when the token is bound to a
        // key, since nothing else needs such an exemption, and per call because it is bound to the token
        // below: one captured elsewhere buys nothing for any other token.
        assertion <- EdgeAssertion.issue(
          edgeId = config.id,
          keyId = config.keyId,
          privateKey = config.privateKey,
          accessToken = accessToken.toString,
        ).when(binding == SSOClient.TokenBinding.Key)

        // RFC 8705 §3: the other binding is answered by the connection rather than by a
        // header -- auth hashes the certificate this handshake presents and refuses the token
        // unless it is the one `cnf.x5t#S256` names. The same certificate this client
        // authenticates with, which is why there is nothing to exempt it from here: proving
        // the binding and authenticating are the same act.
        connection <- binding match
          case SSOClient.TokenBinding.Certificate(clientId, ClientCredential.MutualTls(certificate)) =>
            mutualTlsConnection(clientId, certificate, userInfoUrl).asSome
          case SSOClient.TokenBinding.Certificate(clientId, _) =>
            ZIO.fail(SSOClient.TokenBoundToAbsentCertificate(clientId))
          case SSOClient.TokenBinding.Key | SSOClient.TokenBinding.Unbound =>
            ZIO.none

        request = Request
          .get(userInfoUrl)
          .addHeader(Header.Authorization.Bearer(accessToken.toString))
          .addHeaders(assertion.fold(Headers.empty)(a => Headers(Header.Custom(EdgeAssertion.HeaderName, a))))

        response <- ZIO.scoped(connection.fold(httpClient)(httpClient.ssl).request(request))

        result <-
          if response.status.isSuccess then
            response.bodyAs[Json].flatMap {
              case obj: Json.Obj =>
                ZIO.succeed(obj)

              case _ =>
                ZIO.fail(
                  new RuntimeException("UserInfo endpoint returned non-object JSON"),
                )
            }
          else if response.status == Status.Unauthorized then
            ZIO.fail(SSOClient.UserInfoUnauthorized)
          else
            ZIO.fail(
              new RuntimeException(
                s"UserInfo request failed with status ${response.status.code}",
              ),
            )
      yield result
