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
import versola.util.{Base64, Base64Url, CoreConfig, FormDecoder, Secret}
import zio.*
import zio.http.*
import zio.json.*
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
    for
      now <- Clock.instant
      accessTokenString <- tokens.accessTokenJwtProperties match
        case None =>
          ZIO.succeed(Base64.urlEncode(tokens.accessToken))

        case Some(props) =>
          buildJWT(
            accessToken = tokens.accessToken,
            ttl = tokens.accessTokenTtl,
            userId = props.userId,
            config = config.jwt
          )

    yield TokenResponse(
      accessToken = accessTokenString,
      tokenType = "Bearer",
      expiresIn = tokens.accessTokenTtl.toSeconds,
      refreshToken = tokens.refreshToken.map(Base64.urlEncode),
      scope = Option.when(tokens.scope.nonEmpty)(tokens.scope.mkString(" ")),
    )

  private def buildJWT(
      accessToken: AccessToken,
      ttl: Duration,
      userId: UserId,
      config: JwtConfig,
  ) =
    Clock.instant.flatMap: now =>
      ZIO.attemptBlocking:
        val claims = JWTClaimsSet.Builder()
          .issuer(config.issuer)
          .subject(userId.toString)
          .jwtID(Base64Url.encode(accessToken))
          .issueTime(Date.from(now))
          .expirationTime(Date.from(now.plusSeconds(ttl.toSeconds)))
          .build()

        val header = JWSHeader.Builder(JWSAlgorithm.RS256)
          .`type`(JOSEObjectType("at+jwt"))
          .keyID(config.jwkSet.getKeys.get(0).getKeyID)
          .build()

        val jwt = SignedJWT(header, claims)
        val signer = RSASSASigner(config.privateKey)
        jwt.sign(signer)
        jwt.serialize()

  private def parseRequest(request: Request): IO[TokenEndpointError, TokenRequest] =
    for
      form <- request.body.asURLEncodedForm.orElseFail(TokenEndpointError.InvalidRequest)
      request <- form.get("grant_type").flatMap(_.stringValue) match
        case Some("authorization_code") =>
          ZIO.fromEither(codeExchangeRequestDecoder.decode(form)).orElseFail(TokenEndpointError.InvalidRequest)
        case _ =>
          ZIO.fail(TokenEndpointError.UnsupportedGrantType)
    yield request

  val codeExchangeRequestDecoder: FormDecoder[CodeExchangeRequest] = (form: Form) =>
    for
      code <- FormDecoder.single(form, "code", AuthorizationCode.fromBase64Url)
      redirectUri <- FormDecoder.single(form, "redirect_uri", URL.decode(_).left.map(_.getMessage))
      codeVerifier <- FormDecoder.single(form, "code_verifier", CodeVerifier.from)
    yield CodeExchangeRequest(code, redirectUri, codeVerifier)
