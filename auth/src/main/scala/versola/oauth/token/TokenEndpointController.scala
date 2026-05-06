package versola.oauth.token

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ScopeToken
import versola.oauth.model.{AccessToken, AuthorizationCode, CodeVerifier, RefreshToken}
import versola.oauth.token.model.{ClientCredentialsRequest, CodeExchangeRequest, IssuedTokens, RefreshTokenRequest, TokenEndpointError, TokenErrorResponse, TokenRequest, TokenResponse}
import versola.oauth.userinfo.UserInfoService
import versola.user.model.UserId
import versola.util.CoreConfig.JwtConfig
import versola.util.http.{Controller, extractCredentials}
import versola.util.{Base64, Base64Url, CoreConfig, FormDecoder, JWT, Secret}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

import java.time.Instant
import java.util.Date

object TokenEndpointController extends Controller:
  type Env = Tracing & OAuthTokenService & OAuthConfigurationService & UserInfoService & CoreConfig

  def routes: Routes[Env, Throwable] = Routes(
    tokenEndpoint,
  )

  val tokenEndpoint =
    Method.POST / "v1" / "token" -> handler { (request: Request) =>
      (for
        oauthTokenService <- ZIO.service[OAuthTokenService]
        config <- ZIO.service[CoreConfig]
        tokenRequest <- parseRequest(request)
        credentials <- request.extractCredentials.orElseFail(TokenEndpointError.InvalidClient)
        issuedTokens <- tokenRequest match
          case codeExchangeRequest: CodeExchangeRequest =>
            oauthTokenService.exchangeAuthorizationCode(codeExchangeRequest, credentials)
          case refreshTokenRequest: RefreshTokenRequest =>
            oauthTokenService.refreshAccessToken(refreshTokenRequest, credentials)
          case clientCredentialsRequest: ClientCredentialsRequest =>
            oauthTokenService.clientCredentials(clientCredentialsRequest, credentials)
        response <- toTokenResponse(issuedTokens, config)
      yield Response.json(response.toJson))
        .catchAll {
          case error: TokenEndpointError =>
            ZIO.succeed:
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
  ): ZIO[UserInfoService, Throwable, TokenResponse] =
    import versola.oauth.userinfo.model.RequestedClaims.given
    for
      now <- Clock.instant

      customClaims = Map(
        "client_id" -> Json.Str(tokens.clientId),
        "scope" -> Json.Str(tokens.scope.mkString(" ")),
        "jti" -> Json.Str(Base64Url.encode(tokens.accessToken)),
      ) ++
        tokens.requestedClaims.map(rc => "requested_claims" -> rc.toJsonAST.toOption.get) ++
        tokens.uiLocales.map(locales => "ui_locales" -> Json.Arr(locales.map(Json.Str(_))*))

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
          publicKeys = config.jwt.publicKeys,
          privateKey = config.jwt.privateKey,
        ),
      )
      idToken <- generateIdToken(tokens, config)
    yield TokenResponse(
      accessToken = serializedAT,
      tokenType = "Bearer",
      expiresIn = tokens.accessTokenTtl.toSeconds,
      refreshToken = tokens.refreshToken.map(Base64.urlEncode),
      scope = Option.when(tokens.scope.nonEmpty)(tokens.scope.mkString(" ")),
      idToken = idToken,
    )

  private def generateIdToken(tokens: IssuedTokens, config: CoreConfig): ZIO[UserInfoService, Throwable, Option[String]] =
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

          serializedIdToken <- JWT.serialize(
            typ = JWT.Type.JWT,
            claims = JWT.Claims(
              issuer = config.jwt.issuer,
              subject = userId.toString,
              audience = List(tokens.clientId),
              custom = Json.Obj(Chunk.fromIterable(userInfo.claims)),
            ),
            ttl = tokens.accessTokenTtl,
            signature = JWT.Signature.Asymmetric(
              publicKeys = config.jwt.publicKeys,
              privateKey = config.jwt.privateKey,
            ),
          )
        yield Some(serializedIdToken)

      case _ =>
        ZIO.none

  private def parseRequest(request: Request): IO[TokenEndpointError, TokenRequest] =
    for
      form <- request.body.asURLEncodedForm.orElseFail(TokenEndpointError.InvalidRequest)
      request <- form.get("grant_type").flatMap(_.stringValue) match
        case Some("authorization_code") =>
          codeExchangeRequestDecoder.decode(form).orElseFail(TokenEndpointError.InvalidRequest)
        case Some("refresh_token") =>
          refreshTokenRequestDecoder.decode(form).orElseFail(TokenEndpointError.InvalidRequest)
        case Some("client_credentials") =>
          clientCredentialsRequestDecoder.decode(form).orElseFail(TokenEndpointError.InvalidRequest)
        case _ =>
          ZIO.fail(TokenEndpointError.UnsupportedGrantType)
    yield request

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
    yield RefreshTokenRequest(refreshToken, scope)

  val clientCredentialsRequestDecoder: FormDecoder[ClientCredentialsRequest] = (form: Form) =>
    for
      scope <- FormDecoder.optional(form, "scope", scope => Right(ScopeToken.parseTokens(scope)))
    yield ClientCredentialsRequest(scope)
