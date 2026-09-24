package versola.edge

import versola.edge.model.{AccessToken, AuthorizationPreset, ClientCredential, ClientId, Code, CodeVerifier, OAuthClient, RefreshToken, State, TokenResponse}
import versola.util.{Base64, ClientAssertion, EdgeAssertion, RedirectUri, RequestObject, Secret}
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

  /** `dpopBound` names what the caller already knows about `accessToken` -- whether its
    * `cnf.jkt` is set -- so this can skip minting an assertion for the common case of a plain
    * bearer token, which auth would accept over `Bearer` without one anyway. */
  def userInfo(
      accessToken: AccessToken,
      dpopBound: Boolean,
  ): IO[Throwable | SSOClient.UserInfoUnauthorized.type, Json.Obj]

object SSOClient:
  case object InvalidGrant
  case object UserInfoUnauthorized

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

  /** RFC 9126 §2.2: what `/par` hands back in place of the request. */
  private case class PushedAuthorizationResponse(
      @jsonField("request_uri") requestUri: String,
  ) derives JsonCodec

  private case class ErrorResponse(
      error: String,
      @jsonField("error_description") errorDescription: Option[String] = None,
  ) derives JsonCodec

  val live: URLayer[Client & EdgeConfig, SSOClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
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
        response <- ZIO.scoped(httpClient.request(request))
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

    /** A form and the headers that authenticate it as `clientId`.
      *
      * The two methods land in different places -- RFC 6749 §2.3.1 puts a secret in the
      * `Authorization` header, RFC 7523 §2.2 puts an assertion in the body -- so this hands
      * back both rather than one, and every authenticated call goes through it instead of
      * each deciding for itself.
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
            Authenticated(
              form
                .append(FormField.simpleField("client_id", clientId))
                .append(FormField.simpleField("client_assertion_type", ClientAssertion.Type))
                .append(FormField.simpleField("client_assertion", assertion)),
              Headers.empty,
            )

    private case class Authenticated(form: Form, headers: Headers)

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
        response <- ZIO.scoped(httpClient.request(request))
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
        response <- ZIO.scoped(httpClient.request(request))
        result <-
          if response.status.isSuccess then response.bodyAs[TokenResponse]
          else
            response.bodyAs[ErrorResponse].flatMap: error =>
              if error.error == "invalid_grant" then ZIO.fail(InvalidGrant)
              else ZIO.fail(new RuntimeException(s"Token exchange failed: ${response.status.code} ${error.error}"))
      yield result

    override def userInfo(
        accessToken: AccessToken,
        dpopBound: Boolean,
    ): IO[Throwable | SSOClient.UserInfoUnauthorized.type, Json.Obj] =
      for
        // auth enforces RFC 9449 §7 on this token too, and this call carries no proof of its
        // own: the client's key signed the one edge already checked, and edge does not hold
        // that key to mint another. The assertion is how auth tells this call apart from the
        // `Bearer` downgrade of a bound token -- minted only when the token is bound, since an
        // unbound token needs no such exemption, and per call because it is bound to the token
        // below: one captured elsewhere buys nothing for any other token.
        assertion <- EdgeAssertion.issue(
          edgeId = config.id,
          keyId = config.keyId,
          privateKey = config.privateKey,
          accessToken = accessToken.toString,
        ).when(dpopBound)
        request = Request
          .get(userInfoUrl)
          .addHeader(Header.Authorization.Bearer(accessToken.toString))
          .addHeaders(assertion.fold(Headers.empty)(a => Headers(Header.Custom(EdgeAssertion.HeaderName, a))))

        response <- ZIO.scoped(httpClient.request(request))

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
