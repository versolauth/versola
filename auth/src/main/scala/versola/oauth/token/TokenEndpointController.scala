package versola.oauth.token

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthMethodRef, AuthorizationDetail, ClientCredentials, ClientId, ClientIdWithSecret, ResourceUri, ScopeToken}
import versola.oauth.dpop.DpopService
import versola.oauth.mtls.{CertificateRelevance, ClientAuthentication, ClientCertificate}
import versola.oauth.jwks.JwksService
import versola.oauth.model.{AccessToken, AuthorizationCode, CodeVerifier, RefreshToken}
import versola.oauth.token.model.{ClientCredentialsRequest, CodeExchangeRequest, IssuedTokens, RefreshTokenRequest, TokenEndpointError, TokenErrorResponse, TokenRequest, TokenResponse}
import versola.oauth.userinfo.UserInfoService
import versola.user.model.UserId
import versola.util.CoreConfig.JwtConfig
import versola.util.http.{Controller, Observability, extractCredentials}
import versola.util.{Base64, Base64Url, CoreConfig, FormDecoder, JWT, Secret}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

import java.time.Instant
import java.util.Date

object TokenEndpointController extends Controller:
  type Env = Tracing & OAuthTokenService & OAuthConfigurationService & ClientAuthentication & UserInfoService & JwksService & DpopService & CoreConfig

  private val DpopHeader = "DPoP"
  private val DpopNonceHeader = "DPoP-Nonce"

  /** RFC 9449 §4.3 compares a proof's `htu` against the endpoint's own URI. It is derived from
    * the configured issuer rather than from the inbound request, so a forwarded host header
    * can't be used to make a proof minted for some other origin validate here. */
  private def tokenEndpointUri(config: CoreConfig): String =
    s"${config.jwt.issuer.stripSuffix("/")}/token"

  /** draft-ietf-httpapi-idempotency-key-header. Only honoured for `refresh_token`: that is
    * the grant where losing a response costs the client its session rather than one request. */
  private val IdempotencyKeyHeader = "Idempotency-Key"

  def routes: Routes[Env, Throwable] = Routes(
    tokenEndpoint,
  )

  val tokenEndpoint =
    Method.POST / "token" -> handler { (request: Request) =>
      (for
        oauthTokenService <- ZIO.service[OAuthTokenService]
        config <- ZIO.service[CoreConfig]
        signingKey <- ZIO.serviceWithZIO[JwksService](_.signingKey)
        form <- request.body.asURLEncodedForm.orElseFail(TokenEndpointError.InvalidRequest)
        tokenRequest <- parseRequest(form)
        credentials <- request.extractCredentials(form).orElseFail(TokenEndpointError.InvalidClient)
        dpopJkt <- verifyDpopProof(request, config, credentials.clientId)
        certificate <- ZIO.serviceWithZIO[ClientAuthentication](
          _.certificate(
            request = request,
            credentials = credentials,
            // Wider than the other endpoints': a certificate matters here even to a client
            // that authenticates by secret, because RFC 8705 §3 may bind the tokens it is
            // about to be issued to it.
            relevance = CertificateRelevance.TokenIssuance,
          ).mapError(TokenEndpointError.InvalidClientCertificate(_)),
        )
        issuedTokens <- tokenRequest match
          case codeExchangeRequest: CodeExchangeRequest =>
            oauthTokenService.exchangeAuthorizationCode(codeExchangeRequest, credentials, dpopJkt, certificate)
          case refreshTokenRequest: RefreshTokenRequest =>
            oauthTokenService.refreshAccessToken(
                refreshTokenRequest,
                credentials,
                dpopJkt,
                certificate,
                request.headers.get(IdempotencyKeyHeader),
            )
          case clientCredentialsRequest: ClientCredentialsRequest =>
            oauthTokenService.clientCredentials(clientCredentialsRequest, credentials, dpopJkt, certificate)
        response <- toTokenResponse(issuedTokens, config, signingKey)
      yield Response.json(response.toJson))
        .catchAll {
          case error: TokenEndpointError =>
            Observability.setError(error.error, error.logDescription).as:
              val errorResponse = TokenErrorResponse.from(error)
              val response = Response
                .json(errorResponse.toJson)
                .status(error.status)
                .addHeader(Header.CacheControl.NoStore)
                .addHeader(Header.Pragma.NoCache)
              error match
                case TokenEndpointError.UseDpopNonce(nonce) =>
                  response.addHeader(Header.Custom(DpopNonceHeader, nonce))
                case _ => response

          case error: Throwable =>
            ZIO.fail(error)
        }
    }

  /** RFC 9449 §5: DPoP is opt-in per request here -- a request without a proof still yields
    * bearer tokens. Whether a given client is *required* to use DPoP is a separate, per-client
    * policy decision that isn't wired up yet.
    *
    * Whether a proof must also carry a server nonce (§8) is the requesting client's tenant
    * setting. It is read from the client named in the request's credentials rather than from
    * anything in the proof, so a client cannot pick the answer; where it is set, the refusal
    * below is `use_dpop_nonce` and carries one to retry with.
    */
  private def verifyDpopProof(
      request: Request,
      config: CoreConfig,
      clientId: ClientId,
  ): ZIO[DpopService & OAuthConfigurationService, Throwable | TokenEndpointError, Option[String]] =
    request.headers.toList.filter(_.headerName.equalsIgnoreCase(DpopHeader)).map(_.renderedValue) match
      case Nil =>
        ZIO.none
      case proofs if proofs.size != 1 =>
        ZIO.fail(TokenEndpointError.InvalidDpopProof("request must contain exactly one DPoP header"))
      case proof :: Nil =>
        for
          requireNonce <- ZIO.serviceWithZIO[OAuthConfigurationService](_.requireDpopNonce(clientId))
          verified <- ZIO.serviceWithZIO[DpopService](
            _.verify(
              token = proof,
              method = Method.POST,
              uri = tokenEndpointUri(config),
              requireNonce = requireNonce,
            ),
          ).mapError {
            case DpopService.Error.InvalidProof(reason) => TokenEndpointError.InvalidDpopProof(reason.toString)
            case DpopService.Error.Replayed => TokenEndpointError.InvalidDpopProof("proof has already been used")
            case DpopService.Error.NonceRequired(nonce) => TokenEndpointError.UseDpopNonce(nonce)
            case error: Throwable => error
          }
        yield Some(verified.jkt)

  private def toTokenResponse(
      tokens: IssuedTokens,
      config: CoreConfig,
      signingKey: JWT.Signature.Asymmetric,
  ): ZIO[UserInfoService, Throwable, TokenResponse] =
    import versola.oauth.userinfo.model.RequestedClaims.given
    for
      now <- Clock.instant

      customClaims = Map(
        "client_id" -> Json.Str(tokens.clientId),
        "scope" -> Json.Str(tokens.scope.mkString(" ")),
        "jti" -> Json.Str(Base64Url.encode(tokens.accessToken)),
        "roles" -> Json.Arr(tokens.roles.map(Json.Str(_))*),
        "tenant_id" -> Json.Str(tokens.tenantId),
      ) ++
        tokens.sessionId.map(sid => "sid" -> Json.Str(sid)) ++
        tokens.refreshTokenFamilyId.map(family => "fam" -> Json.Str(family)) ++
        tokens.cnf.map("cnf" -> _.toJsonObj) ++
        tokens.requestedClaims.map(rc => "requested_claims" -> rc.toJsonAST.toOption.get) ++
        authorizationDetailsClaim(tokens).map("authorization_details" -> _) ++
        AuthMethodRef.idTokenClaims(tokens.amr, tokens.authTime, tokens.acr)


      // For client_credentials grant, use client_id as subject; otherwise use user_id
      subject = tokens.userId.map(_.toString).getOrElse(tokens.clientId)

      serializedAT <- JWT.serialize(
        typ = JWT.Type.AccessToken,
        claims = JWT.Claims(
          issuer = config.jwt.issuer,
          subject = subject,
          audience = tokens.audience,
          custom = Json.Obj(customClaims.toSeq*),
        ),
        ttl = tokens.accessTokenTtl,
        signature = signingKey,
      )
      idToken <- generateIdToken(tokens, config, signingKey, serializedAT)
    yield TokenResponse(
      accessToken = serializedAT,
      // RFC 9449 §5: a DPoP-bound token is presented with the `DPoP` scheme, not `Bearer`.
      // Only that binding changes the scheme -- RFC 8705 §3.1 leaves a certificate-bound
      // token a `Bearer` one, since the constraint travels on the TLS connection.
      tokenType = if tokens.cnf.exists(_.jkt.isDefined) then "DPoP" else "Bearer",
      expiresIn = tokens.accessTokenTtl.toSeconds,
      refreshToken = tokens.refreshToken.map(Base64.urlEncode),
      scope = Option.when(tokens.scope.nonEmpty)(tokens.scope.mkString(" ")),
      idToken = idToken,
      authorizationDetails = authorizationDetailsClaim(tokens),
    )

  /** RFC 9396 §7: the granted authorization details are returned in the token response and
    * carried in the access token, unchanged from how they were granted. */
  private def authorizationDetailsClaim(tokens: IssuedTokens): Option[Json.Arr] =
    Option.when(tokens.authorizationDetails.nonEmpty)(
      Json.Arr(tokens.authorizationDetails.map(_.value)*),
    )

  private def generateIdToken(
      tokens: IssuedTokens,
      config: CoreConfig,
      signingKey: JWT.Signature.Asymmetric,
      accessToken: String,
  ): ZIO[UserInfoService, Throwable, Option[String]] =
    (tokens.user, tokens.userId) match
      case (Some(user), Some(userId)) if tokens.scope.contains(ScopeToken.OpenId) =>
        for
          userInfoService <- ZIO.service[UserInfoService]

          userInfo <- userInfoService.getUserInfoForIdToken(
            user = user,
            scope = tokens.scope,
            requestedClaims = tokens.requestedClaims,
            uiLocales = tokens.uiLocales,
            nonce = tokens.nonce,
          )
          atHash = JWT.leftHalfHash(accessToken, signingKey.algorithm)
          sidClaim = tokens.sessionId.map(sid => "sid" -> Json.Str(sid))
          serializedIdToken <- JWT.serialize(
            typ = JWT.Type.JWT,
            claims = JWT.Claims(
              issuer = config.jwt.issuer,
              subject = userId.toString,
              audience = List(tokens.clientId),
              custom = Json.Obj(Chunk.fromIterable(
                userInfo.claims ++
                  AuthMethodRef.idTokenClaims(tokens.amr, tokens.authTime, tokens.acr) ++ sidClaim +
                  ("at_hash" -> Json.Str(atHash)),
              )),
            ),
            ttl = tokens.accessTokenTtl,
            signature = signingKey,
          )
        yield Some(serializedIdToken)

      case _ =>
        ZIO.none

  private def parseRequest(form: Form): IO[TokenEndpointError, TokenRequest] =
    form.get("grant_type").flatMap(_.stringValue) match
      case Some(grantType @ "authorization_code") =>
        Observability.setRouteLabel("grant_type", grantType) *>
          codeExchangeRequestDecoder.decode(form).orElseFail(TokenEndpointError.InvalidRequest)
      case Some(grantType @ "refresh_token") =>
        Observability.setRouteLabel("grant_type", grantType) *>
          refreshTokenRequestDecoder.decode(form).orElseFail(TokenEndpointError.InvalidRequest)
      case Some(grantType @ "client_credentials") =>
        Observability.setRouteLabel("grant_type", grantType) *>
          clientCredentialsRequestDecoder.decode(form).orElseFail(TokenEndpointError.InvalidRequest)
      case _ =>
        ZIO.fail(TokenEndpointError.UnsupportedGrantType)

  val codeExchangeRequestDecoder: FormDecoder[CodeExchangeRequest] = (form: Form) =>
    for
      code <- FormDecoder.single(form, "code", AuthorizationCode.fromBase64Url)
      redirectUri <- FormDecoder.single(form, "redirect_uri", URL.decode(_).left.map(_.getMessage))
      codeVerifier <- FormDecoder.single(form, "code_verifier", CodeVerifier.from)
    yield CodeExchangeRequest(code, redirectUri, codeVerifier)

  val refreshTokenRequestDecoder: FormDecoder[RefreshTokenRequest] = (form: Form) =>
    for
      refreshToken <- FormDecoder.single(form, "refresh_token", RefreshToken.fromBase64Url)
      scope <- FormDecoder.optional(form, "scope", scope => Right(ScopeToken.parseTokens(scope)))
      resources <- resourceRequestDecoder(form)
      authorizationDetails <- authorizationDetailsRequestDecoder(form)
    yield RefreshTokenRequest(refreshToken, scope, resources, authorizationDetails)

  val clientCredentialsRequestDecoder: FormDecoder[ClientCredentialsRequest] = (form: Form) =>
    for
      scope <- FormDecoder.optional(form, "scope", scope => Right(ScopeToken.parseTokens(scope)))
      resources <- resourceRequestDecoder(form)
      authorizationDetails <- authorizationDetailsRequestDecoder(form)
    yield ClientCredentialsRequest(scope, resources, authorizationDetails)

  private def authorizationDetailsRequestDecoder(form: Form): IO[String, Option[List[AuthorizationDetail]]] =
    FormDecoder.optional(form, AuthorizationDetail.Parameter, AuthorizationDetail.parseAll)

  private def resourceRequestDecoder(form: Form): IO[String, Option[List[ResourceUri]]] =
    val resourceFields = form.formData.filter(_.name == "resource")
    val resourceValues = resourceFields.flatMap: field =>
      field.stringValue.toList.flatMap(ResourceUri.splitFormValue).filter(_.nonEmpty)
    ZIO.foreach(resourceValues)(value => ZIO.fromEither(ResourceUri.parse(value)))
      .map(resources => Option.when(resourceFields.nonEmpty)(resources.toList))
