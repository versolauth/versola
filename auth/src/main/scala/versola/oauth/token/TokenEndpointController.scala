package versola.oauth.token

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthMethodRef, ResourceUri, ScopeToken}
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
  type Env = Tracing & OAuthTokenService & OAuthConfigurationService & UserInfoService & JwksService & CoreConfig

  def routes: Routes[Env, Throwable] = Routes(
    tokenEndpoint,
  )

  val tokenEndpoint =
    Method.POST / "token" -> handler { (request: Request) =>
      (for
        oauthTokenService <- ZIO.service[OAuthTokenService]
        config <- ZIO.service[CoreConfig]
        signingKey <- ZIO.serviceWithZIO[JwksService](_.getPublicKeys).map(_.active)
        form <- request.body.asURLEncodedForm.orElseFail(TokenEndpointError.InvalidRequest)
        tokenRequest <- parseRequest(form)
        credentials <- request.extractCredentials(form).orElseFail(TokenEndpointError.InvalidClient)
        issuedTokens <- tokenRequest match
          case codeExchangeRequest: CodeExchangeRequest =>
            oauthTokenService.exchangeAuthorizationCode(codeExchangeRequest, credentials)
          case refreshTokenRequest: RefreshTokenRequest =>
            oauthTokenService.refreshAccessToken(refreshTokenRequest, credentials)
          case clientCredentialsRequest: ClientCredentialsRequest =>
            oauthTokenService.clientCredentials(clientCredentialsRequest, credentials)
        response <- toTokenResponse(issuedTokens, config, signingKey)
      yield Response.json(response.toJson))
        .catchAll {
          case error: TokenEndpointError =>
            Observability.setError(error.error, error.errorDescription).as:
              val errorResponse = TokenErrorResponse.from(error)
              Response
                .json(errorResponse.toJson)
                .status(error.status)
                .addHeader(Header.CacheControl.NoStore)
                .addHeader(Header.Pragma.NoCache)

          case error: Throwable =>
            ZIO.fail(error)
        }
    }

  private def toTokenResponse(
      tokens: IssuedTokens,
      config: CoreConfig,
      signingKey: JWT.PublicKey,
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
        tokens.requestedClaims.map(rc => "requested_claims" -> rc.toJsonAST.toOption.get) ++
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
        signature = JWT.Signature.Asymmetric(
          algorithm = signingKey.algorithm,
          keyId = signingKey.id,
          privateKey = config.jwt.privateKey,
        ),
      )
      idToken <- generateIdToken(tokens, config, signingKey, serializedAT)
    yield TokenResponse(
      accessToken = serializedAT,
      tokenType = "Bearer",
      expiresIn = tokens.accessTokenTtl.toSeconds,
      refreshToken = tokens.refreshToken.map(Base64.urlEncode),
      scope = Option.when(tokens.scope.nonEmpty)(tokens.scope.mkString(" ")),
      idToken = idToken,
    )

  private def generateIdToken(
      tokens: IssuedTokens,
      config: CoreConfig,
      signingKey: JWT.PublicKey,
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
            signature = JWT.Signature.Asymmetric(
              algorithm = signingKey.algorithm,
              keyId = signingKey.id,
              privateKey = config.jwt.privateKey,
            ),
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
    yield RefreshTokenRequest(refreshToken, scope, resources)

  val clientCredentialsRequestDecoder: FormDecoder[ClientCredentialsRequest] = (form: Form) =>
    for
      scope <- FormDecoder.optional(form, "scope", scope => Right(ScopeToken.parseTokens(scope)))
      resources <- resourceRequestDecoder(form)
    yield ClientCredentialsRequest(scope, resources)

  private def resourceRequestDecoder(form: Form): IO[String, Option[List[ResourceUri]]] =
    val resourceFields = form.formData.filter(_.name == "resource")
    val resourceValues = resourceFields.flatMap: field =>
      field.stringValue.toList.flatMap(ResourceUri.splitFormValue).filter(_.nonEmpty)
    ZIO.foreach(resourceValues)(value => ZIO.fromEither(ResourceUri.parse(value)))
      .map(resources => Option.when(resourceFields.nonEmpty)(resources.toList))
