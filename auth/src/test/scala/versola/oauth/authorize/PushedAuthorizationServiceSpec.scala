package versola.oauth.authorize

import versola.auth.TestEnvConfig
import versola.oauth.authorize.model.{AuthorizeRequest, Error, PushedAuthorizationError, ResponseTypeEntry}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.*
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, RequestUri}
import versola.oauth.clientauth.{ClientAssertionService, ClientAuthentication}
import versola.util.{Secret, SecureRandom, SecurityService, UnitSpecBase}
import zio.*
import zio.http.{Request, URL}
import zio.prelude.NonEmptySet
import zio.test.*

object PushedAuthorizationServiceSpec extends UnitSpecBase:

  private val clientId = ClientId("test-client")
  private val clientSecret = versola.util.Secret(Array.fill(32)(4.toByte))
  private val redirectUri = URL.decode("https://example.com/callback").toOption.get
  private val config = TestEnvConfig.coreConfig

  private val clientRecord = OAuthClientRecord(
    id = clientId,
    tenantId = TenantId("default"),
    clientName = Map("en" -> "Test Client"),
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("openid")),
    secret = Some(clientSecret),
    previousSecret = None,
    accessTokenTtl = 1.hour,
    refreshTokenTtl = 30.days,
    theme = "default",
    authFlow = None,
    registrationFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
    dpopBoundAccessTokens = false,
    mtlsAuth = None,
    certificateBoundAccessTokens = false,
    jwks = None,
  )

  private val parsedRequest: AuthorizeRequest = AuthorizeRequest(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = Set(ScopeToken("openid")),
    state = None,
    codeChallenge = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),
    codeChallengeMethod = CodeChallengeMethod.S256,
    responseType = NonEmptySet(ResponseTypeEntry.Code),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    userAgent = None,
    userAgentCookie = None,
    prompt = Set.empty,
    maxAge = None,
    acrValues = None,
    sessionId = None,
    loginHint = None,
    idTokenHint = None,
    resources = Nil,
    authorizationDetails = None,
    dpopJkt = None,
  )

  private val credentials = ClientIdWithSecret(clientId, Some(clientSecret))

  private def validParams(extra: (String, Chunk[String])*): Map[String, Chunk[String]] =
    Map(
      "client_id" -> Chunk(clientId: String),
      "response_type" -> Chunk("code"),
      "redirect_uri" -> Chunk(redirectUri.encode),
      "scope" -> Chunk("openid"),
      "code_challenge" -> Chunk("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),
      "code_challenge_method" -> Chunk("S256"),
    ) ++ extra

  private class Env:
    val parser = stub[AuthorizeRequestParser]
    val repository = stub[PushedAuthorizationRepository]
    val configuration = stub[OAuthConfigurationService]
    // Authentication looks the client up first to see whether it registered an mTLS
    // subject; an unregistered one falls through to the secret it presented.
    configuration.find.returnsWith(ZIO.none)
    val clientAuthentication = ClientAuthentication.Impl(configuration, stub[ClientAssertionService], TestEnvConfig.coreConfig)

    def service: UIO[PushedAuthorizationService] =
      SecureRandom.live.build.flatMap { env =>
        val secureRandom = env.get[SecureRandom]
        Semaphore.make(1).map { hashingSemaphore =>
          PushedAuthorizationService.Impl(
            config,
            parser,
            repository,
            clientAuthentication,
            secureRandom,
            SecurityService.Impl(secureRandom, hashingSemaphore),
          )
        }
      }.provideLayer(zio.Scope.default)

    def happyPath: UIO[Unit] =
      for
        _ <- configuration.verifySecret.succeedsWith(Some(clientRecord))
        _ <- parser.validate.succeedsWith(parsedRequest)
        _ <- repository.create.succeedsWith(())
      yield ()

  /** RFC 8705 §2.1: authenticates by certificate, so it holds no secret. */
  private val mtlsClientRecord = clientRecord.copy(
    secret = None,
    mtlsAuth = Some(MutualTlsAuth(MutualTlsSubjectType.san_dns, TestEnvConfig.clientCertificateDnsName)),
  )

  private val request = Request.get(URL.empty / "par")

  def spec = suite("PushedAuthorizationService")(
    test("returns a request_uri with the configured lifetime") {
      val env = Env()
      for
        _ <- env.happyPath
        service <- env.service
        response <- service.push(validParams(), credentials, None, request)
      yield assertTrue(
        response.requestUri.startsWith(RequestUri.Prefix),
        response.expiresIn == config.parOrDefault.requestUriTtl.toSeconds,
      )
    },
    test("stores the pushed parameters bound to the authenticated client") {
      val env = Env()
      for
        _ <- env.happyPath
        service <- env.service
        response <- service.push(validParams("state" -> Chunk("test-state")), credentials, None, request)
        created = env.repository.create.calls
      yield assertTrue(
        created.size == 1,
        created.head._2.clientId == clientId,
        created.head._2.params.get("state") == Some(List("test-state")),
        created.head._3 == config.parOrDefault.requestUriTtl,
      )
    },
    test("never persists the client authentication parameters") {
      val env = Env()
      for
        _ <- env.happyPath
        service <- env.service
        _ <- service.push(
          validParams("client_secret" -> Chunk("super-secret")),
          credentials,
          None,
          request,
        )
        created = env.repository.create.calls.head._2
        validated = env.parser.validate.calls.head._1
      yield assertTrue(
        !created.params.contains("client_secret"),
        !validated.contains("client_secret"),
      )
    },
    test("stores the request_uri as a MAC rather than the reference handed to the client") {
      val env = Env()
      for
        _ <- env.happyPath
        secureRandom <- SecureRandom.live.build.map(_.get[SecureRandom]).provideLayer(zio.Scope.default)
        service <- env.service
        response <- service.push(validParams(), credentials, None, request)
        reference <- ZIO.fromEither(RequestUri.parse(response.requestUri)).mapError(RuntimeException(_))
        hashingSemaphore <- Semaphore.make(1)
        expected <- SecurityService.Impl(secureRandom, hashingSemaphore).mac(Secret(reference), config.security.parRequestsSecret)
        stored = env.repository.create.calls.head._1
      yield assertTrue(
        stored.toSeq == expected.toSeq,
        stored.toSeq != reference.toSeq,
      )
    },
    test("fails with invalid_client when the credentials are not valid") {
      val env = Env()
      for
        _ <- env.configuration.verifySecret.succeedsWith(None)
        service <- env.service
        result <- service.push(validParams(), credentials, None, request).either
      yield assertTrue(result == Left(PushedAuthorizationError.InvalidClient))
    },
    test("rejects a request_uri parameter") {
      val env = Env()
      for
        _ <- env.happyPath
        service <- env.service
        result <- service.push(validParams("request_uri" -> Chunk("urn:x")), credentials, None, request).either
      yield assertTrue(result == Left(PushedAuthorizationError.RequestUriNotAllowed))
    },
    test("rejects a request without client_id") {
      val env = Env()
      for
        _ <- env.happyPath
        service <- env.service
        result <- service.push(validParams() - "client_id", credentials, None, request).either
      yield assertTrue(result == Left(PushedAuthorizationError.ClientIdMissing))
    },
    test("reports authorization request validation failures directly") {
      val env = Env()
      for
        _ <- env.configuration.verifySecret.succeedsWith(Some(clientRecord))
        _ <- env.parser.validate.failsWith(Error.ScopeMissing(redirectUri, None, useFragment = false))
        service <- env.service
        result <- service.push(validParams(), credentials, None, request).either
      yield assertTrue(result.left.toOption.exists(_.asInstanceOf[PushedAuthorizationError].error == "invalid_scope"))
    },
    test("authenticates a client by its certificate, with no secret presented") {
      val env = Env()
      for
        _ <- env.configuration.find.succeedsWith(Some(mtlsClientRecord))
        _ <- env.parser.validate.succeedsWith(parsedRequest)
        _ <- env.repository.create.succeedsWith(())
        service <- env.service
        // No secret: the certificate is the credential.
        result <- service.push(
          validParams(),
          ClientIdWithSecret(clientId, None),
          Some(TestEnvConfig.clientCertificate),
          request,
        ).either
      yield assertTrue(
        result.isRight,
        env.configuration.verifySecret.calls.isEmpty,
      )
    },
    test("rejects a certificate whose subject is not the registered one") {
      val env = Env()
      for
        _ <- env.configuration.find.succeedsWith(Some(mtlsClientRecord))
        service <- env.service
        result <- service.push(
          validParams(),
          ClientIdWithSecret(clientId, None),
          Some(TestEnvConfig.otherClientCertificate),
          request,
        ).either
      yield assertTrue(
        result == Left(PushedAuthorizationError.InvalidClient),
        // Nothing was pushed on the strength of a certificate that authenticates nobody.
        env.repository.create.calls.isEmpty,
      )
    },
    test("rejects a certificate-authenticated client that presents none") {
      val env = Env()
      for
        _ <- env.configuration.find.succeedsWith(Some(mtlsClientRecord))
        service <- env.service
        result <- service.push(validParams(), ClientIdWithSecret(clientId, None), None, request).either
      yield assertTrue(
        result == Left(PushedAuthorizationError.InvalidClient),
        // The registered method is the certificate, so no secret can stand in for it.
        env.configuration.verifySecret.calls.isEmpty,
      )
    },
  )
