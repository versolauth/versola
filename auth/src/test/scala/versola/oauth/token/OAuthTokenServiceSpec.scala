package versola.oauth.token

import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, ClientIdWithSecret, OAuthClientRecord, ScopeToken, TenantId}
import versola.oauth.model.{AccessToken, RefreshToken}
import versola.oauth.revoke.AccessTokenRevocationService
import versola.oauth.session.RefreshTokenRepository
import versola.oauth.token.model.{ClientCredentialsRequest, TokenEndpointError}
import versola.oauth.token.AuthorizationCodeRepository
import versola.user.{UserRepository, UserRolesRepository}
import versola.util.{AuthPropertyGenerator, Secret, SecurityService, UnitSpecBase}
import zio.*
import zio.prelude.NonEmptySet
import zio.test.*

object OAuthTokenServiceSpec extends UnitSpecBase:

  private val clientId     = ClientId("service-client")
  private val clientSecret = Secret(Array.fill(32)(5.toByte))
  private val accessToken  = AccessToken(Array.fill(16)(2.toByte))

  private val confidentialClient = OAuthClientRecord(
    id = clientId,
    tenantId = TenantId("default"),
    clientName = "Service Client",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("read"), ScopeToken("write")),
    externalAudience = Nil,
    secret = Some(clientSecret),
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 30.days,
    theme = "default",
    authFlow = None,
    otpTemplateId = "default",
  )

  private val publicClient = confidentialClient.copy(secret = None)

  class Env:
    val authCodeRepo             = stub[AuthorizationCodeRepository]
    val oauthClientService       = stub[OAuthConfigurationService]
    val tokenRepository          = stub[RefreshTokenRepository]
    val revocationService        = stub[AccessTokenRevocationService]
    val securityService          = stub[SecurityService]
    val authPropertyGenerator    = stub[AuthPropertyGenerator]
    val userRepository           = stub[UserRepository]
    val userRolesRepository      = stub[UserRolesRepository]
    val config                   = TestEnvConfig.coreConfig

    val layer =
      ZLayer.succeed(authCodeRepo) ++
      ZLayer.succeed(oauthClientService) ++
      ZLayer.succeed(tokenRepository) ++
      ZLayer.succeed(revocationService) ++
      ZLayer.succeed(securityService) ++
      ZLayer.succeed(authPropertyGenerator) ++
      ZLayer.succeed(userRepository) ++
      ZLayer.succeed(userRolesRepository) ++
      ZLayer.succeed(config) >>> OAuthTokenService.live

  val spec = suite("OAuthTokenService")(
    suite("clientCredentials")(
      test("succeeds for confidential client and returns issued tokens") {
        val env         = Env()
        val credentials = ClientIdWithSecret(clientId, Some(clientSecret))
        (for
          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(confidentialClient))
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(accessToken)
          service <- ZIO.service[OAuthTokenService]
          result  <- service.clientCredentials(ClientCredentialsRequest(None), credentials)
        yield assertTrue(
          result.clientId == clientId,
          result.userId.isEmpty,
          result.refreshToken.isEmpty,
        )).provide(env.layer)
      },

      test("fails with InvalidClient when client is public") {
        val env         = Env()
        val credentials = ClientIdWithSecret(clientId, None)
        (for
          _ <- env.oauthClientService.verifySecret.succeedsWith(Some(publicClient))
          service <- ZIO.service[OAuthTokenService]
          result  <- service.clientCredentials(ClientCredentialsRequest(None), credentials).exit
        yield assertTrue(result.isFailure)).provide(env.layer)
      },

      test("fails with InvalidClient when client not found") {
        val env         = Env()
        val credentials = ClientIdWithSecret(ClientId("unknown"), Some(clientSecret))
        (for
          _ <- env.oauthClientService.verifySecret.succeedsWith(None)
          service <- ZIO.service[OAuthTokenService]
          result  <- service.clientCredentials(ClientCredentialsRequest(None), credentials).exit
        yield assertTrue(result.isFailure)).provide(env.layer)
      },
    ),
  )
