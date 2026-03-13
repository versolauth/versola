package versola.oauth.token

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.auth.model.AccessToken
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.ClientId
import versola.oauth.model.{AuthorizationCode, CodeVerifier}
import versola.oauth.token.model.{CodeExchangeRequest, IssuedTokens, TokenEndpointError, TokenErrorResponse, TokenRequest, TokenResponse}
import versola.user.model.UserId
import versola.util.CoreConfig.JwtConfig
import versola.util.http.{ClientCredentials, ClientIdWithSecret, Controller}
import versola.util.{Base64, Base64Url, CoreConfig, FormDecoder, JWT, Secret}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

import java.time.Instant
import java.util.Date

object TokenEndpointController extends Controller:
  type Env = Tracing & OAuthTokenService & OAuthClientService & CoreConfig

  def routes: Routes[Env, Nothing] = Routes(
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
        response <- toTokenResponse(issuedTokens, config)
      yield Response.json(response.toJson))
        .catchAll {
          case error: TokenEndpointError =>
            ZIO.succeed:
              val errorResponse = TokenErrorResponse.from(error)
              Response
                .json(errorResponse.toJson)
                .status(Status.BadRequest)
                .addHeader(Header.CacheControl.NoStore)

          case ex: Throwable =>
            ZIO.succeed(Response.internalServerError)
        }
    }

  private def toTokenResponse(tokens: IssuedTokens, config: CoreConfig): Task[TokenResponse] =
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

      serializedAT <- JWT.serialize(
        typ = JWT.Type.AccessToken,
        claims = JWT.Claims(
          issuer = config.jwt.issuer,
          subject = tokens.userId.toString,
          audience = tokens.audience,
          custom = Json.Obj(customClaims.toSeq*),
        ),
        ttl = tokens.accessTokenTtl,
        signature = JWT.Signature(
          publicKeys = config.jwt.publicKeys,
          privateKey = config.jwt.privateKey,
        ),
      )
    yield TokenResponse(
      accessToken = serializedAT,
      tokenType = "Bearer",
      expiresIn = tokens.accessTokenTtl.toSeconds,
      refreshToken = tokens.refreshToken.map(Base64.urlEncode),
      scope = Option.when(tokens.scope.nonEmpty)(tokens.scope.mkString(" ")),
    )

  private def parseRequest(request: Request): IO[TokenEndpointError, TokenRequest] =
    for
      form <- request.body.asURLEncodedForm.orElseFail(TokenEndpointError.InvalidRequest)
      request <- form.get("grant_type").flatMap(_.stringValue) match
        case Some("authorization_code") =>
          codeExchangeRequestDecoder.decode(form).orElseFail(TokenEndpointError.InvalidRequest)
        case _ =>
          ZIO.fail(TokenEndpointError.UnsupportedGrantType)
    yield request

  val codeExchangeRequestDecoder: FormDecoder[CodeExchangeRequest] = (form: Form) =>
    for
      code <- FormDecoder.single(form, "code", AuthorizationCode.fromBase64Url)
      redirectUri <- FormDecoder.single(form, "redirect_uri", URL.decode(_).left.map(_.getMessage))
      codeVerifier <- FormDecoder.single(form, "code_verifier", CodeVerifier.from)
    yield CodeExchangeRequest(code, redirectUri, codeVerifier)
