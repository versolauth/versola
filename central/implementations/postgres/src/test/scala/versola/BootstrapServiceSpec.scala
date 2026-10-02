package versola

import org.scalamock.stubs.ZIOStubs
import versola.central.CentralConfig
import versola.central.configuration.challenges.ChallengeSettingsService
import versola.central.configuration.clients.{AuthFactor, AuthFactorType, AuthMethod, ClientId, MutualTlsAuth, MutualTlsSubjectType, OAuthClientRecord, OAuthClientService}
import versola.central.configuration.{InjectRule, InjectTarget}
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.{Curve, JWK}
import versola.util.{Base64Url, EnvName, Phone, Secret, SecureRandom, TestCertificates}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

object BootstrapServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private def endpointId(method: String, path: String) =
    versola.central.configuration.resources.ResourceEndpointId(
      UUID.nameUUIDFromBytes(s"$method $path".getBytes(StandardCharsets.UTF_8)),
    )

  private def mtlsSeed(certificate: String) =
    CentralConfig.BootstrapConfig.CentralAdminMtlsSeed(certificate, "ssl-client-cert", "urlEncodedPem")

  /** The stored `central-admin` a previous boot left behind, varying only the one column
    * [[BootstrapService.authMethodDowngradeRefusal]] reads. */
  private def centralAdminRecord(authMethod: AuthMethod): OAuthClientRecord =
    OAuthClientRecord(
      id = ClientId(CentralConfig.centralClientId),
      tenantId = CentralConfig.defaultTenantId,
      clientName = Map("en" -> "Central Admin"),
      redirectUris = Set.empty,
      scope = Set.empty,
      secret = None,
      previousSecret = None,
      accessTokenTtl = Duration.fromSeconds(3600),
      refreshTokenTtl = Duration.fromSeconds(3600),
      permissions = Set.empty,
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
      dpopSigningAlgs = Set.empty,
      dpopMinRsaKeySize = None,
      authMethod = authMethod,
      mtlsAuth = None,
      certificateBoundAccessTokens = false,
      jwks = None,
      requireSignedRequestObject = false,
      requirePushedAuthorizationRequests = false,
      edgeSigningKey = None,
      edgeClientCertificate = None,
      template = None,
      createdAt = Instant.EPOCH,
    )

  private val secretlessCredential =
    BootstrapService.CentralAdminCredential(AuthMethod.client_secret, None, None, requirePushedAuthorizationRequests = false)

  private val mtlsCredential = BootstrapService.CentralAdminCredential(
    AuthMethod.tls_client_auth,
    Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, "CN=central-admin")),
    None,
    requirePushedAuthorizationRequests = true,
  )

  private val utilityKey = ECKeyGenerator(Curve.P_256).keyID("utils-1").algorithm(JWSAlgorithm.ES256).generate()

  private def jwkOf(key: JWK): Json.Obj = key.toJSONString.fromJson[Json.Obj].toOption.get

  private def utilitySeed(secret: Option[Secret] = None, publicKeyJwk: Option[Json.Obj] = None) =
    CentralConfig.BootstrapConfig.UtilityClientSeed(ClientId("utils"), secret, publicKeyJwk)

  private val utilitySecretCredential =
    BootstrapService.UtilityClientCredential(AuthMethod.client_secret, Some(Secret(Array[Byte](1, 2, 3))), None)

  private val utilityKeyCredential =
    BootstrapService.UtilityClientCredential(AuthMethod.private_key_jwt, None, None)

  def spec = suite("BootstrapService")(
    // The client and challenge-settings caches load before bootstrap seeds central-admin, and
    // the seed's notifications are lost: without this reload edge's first sync misses it.
    test("refreshCachesAfterBootstrap reloads the client and challenge-settings caches") {
      val clients = stub[OAuthClientService]
      val settings = stub[ChallengeSettingsService]
      for
        _ <- clients.refreshNow.succeedsWith(())
        _ <- settings.refreshNow.succeedsWith(())
        _ <- BootstrapService.refreshCachesAfterBootstrap.provide(
          ZLayer.succeed[OAuthClientService](clients),
          ZLayer.succeed[ChallengeSettingsService](settings),
        )
        clientRefreshes = clients.refreshNow.times
        settingsRefreshes = settings.refreshNow.times
      yield assertTrue(clientRefreshes == 1, settingsRefreshes == 1)
    },
    // #353: the default tenant is FAPI 2.0, and tls_client_auth by edge is what it admits for
    // an edge-fronted web client.
    test("registers central-admin as tls_client_auth by its certificate's subject DN, behind PAR, when given one") {
      val certificate = TestCertificates.generate(subject = "CN=central-admin,O=Versola")
      val credential = BootstrapService.centralAdminCredential(Some(mtlsSeed(certificate.bundle)))
      assertTrue(
        credential.map(_.authMethod) == Right(AuthMethod.tls_client_auth),
        credential.map(_.mtlsAuth) == Right(Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, certificate.subjectDn))),
        credential.map(_.edgeClientCertificate.map(_.pem)) == Right(Some(certificate.bundle)),
        credential.map(_.requirePushedAuthorizationRequests) == Right(true),
        credential.exists(_.conformant),
      )
    },
    test("keeps central-admin on client_secret, outside the profile, without a certificate") {
      val credential = BootstrapService.centralAdminCredential(None)
      assertTrue(
        credential.map(_.authMethod) == Right(AuthMethod.client_secret),
        credential.exists(!_.conformant),
      )
    },
    test("refuses to boot on a certificate that could not be presented") {
      assertTrue(BootstrapService.centralAdminCredential(Some(mtlsSeed("not a pem"))).isLeft)
    },
    test("adds an OTP factor and phone outside production") {
      val envName = EnvName.Test("local")

      assertTrue(
        BootstrapService.adminPhone(envName).contains(Phone("+12025551234")),
        BootstrapService.adminAuthFactors(envName) == List(
          AuthFactor(AuthFactorType.otp, required = true),
          AuthFactor(AuthFactorType.passkeyEnroll, required = true),
        ),
        BootstrapService.adminAuthFlow(envName).passkey.isDefined,
      )
    },
    test("does not add an OTP factor or phone in production") {
      assertTrue(
        BootstrapService.adminPhone(EnvName.Prod).isEmpty,
        BootstrapService.adminAuthFactors(EnvName.Prod) == List(
          AuthFactor(AuthFactorType.passkeyEnroll, required = true),
        ),
        BootstrapService.adminAuthFlow(EnvName.Prod).passkey.isDefined,
      )
    },
    test("uses the configured resource secret") {
      val configured = Secret(Array.fill(32)(7.toByte))
      val secureRandom = stub[SecureRandom]

      BootstrapService.resolveResourceSecret(Some(configured), secureRandom)
        .map(secret => assertTrue(Base64Url.encode(secret) == Base64Url.encode(configured)))
    },
    test("generates a resource secret when configuration is absent") {
      val generated = Array.fill(32)(9.toByte)
      val secureRandom = stub[SecureRandom]

      for
        _ <- secureRandom.nextBytes.succeedsWith(generated)
        secret <- BootstrapService.resolveResourceSecret(None, secureRandom)
      yield assertTrue(Base64Url.encode(secret) == Base64Url.encode(Secret(generated)))
    },
    test("resources:manage includes resource secret lifecycle endpoints") {
      assertTrue(
        BootstrapService.resourceManagementEndpointIds.contains(
          endpointId("POST", "/configuration/resources/rotate-secret"),
        ),
        BootstrapService.resourceManagementEndpointIds.contains(
          endpointId("DELETE", "/configuration/resources/previous-secret"),
        ),
      )
    },
    test("registers central's service endpoints outside production only") {
      val nonProd = BootstrapService.centralEndpoints(EnvName.Test("local"))
      val prod = BootstrapService.centralEndpoints(EnvName.Prod)

      assertTrue(
        nonProd.contains("POST" -> "/service/configuration/sync"),
        nonProd.contains("POST" -> "/service/users/outbox/flush"),
        !prod.exists((_, path) => path.startsWith("/service")),
        prod.forall(nonProd.contains),
        nonProd.size == prod.size + 2,
      )
    },
    test("service:operate covers exactly the service endpoints") {
      assertTrue(
        BootstrapService.serviceEndpointIds == Set(
          endpointId("POST", "/service/configuration/sync"),
          endpointId("POST", "/service/users/outbox/flush"),
        ),
      )
    },
    test("auth-settings:manage covers the account page and every one of its APIs") {
      assertTrue(
        BootstrapService.accountEndpointIds == Set(
          endpointId("GET", "/settings"),
          endpointId("DELETE", "/settings/sessions"),
          endpointId("PATCH", "/settings/passkeys"),
          endpointId("DELETE", "/settings/passkeys"),
          endpointId("POST", "/settings/passkeys/register/start"),
          endpointId("POST", "/settings/passkeys/register/finish"),
        ),
      )
    },
      test("account endpoints inject trusted caller context and no step-up policy yet") {
        val expectedQueryInjects = Vector(
          InjectRule(InjectTarget.query, "userId", "token.sub"),
          InjectRule(InjectTarget.query, "clientId", "token.client_id"),
          InjectRule(InjectTarget.query, "sessionId", "token.sid"),
        )
        val expectedBodyInjects = Vector(
          InjectRule(InjectTarget.body, "userId", "token.sub"),
          InjectRule(InjectTarget.body, "clientId", "token.client_id"),
        )
        assertTrue(
          BootstrapService.accountEndpointRecords.forall: endpoint =>
            endpoint.inject == BootstrapService.accountCallerInjects(endpoint.method, endpoint.path),
          BootstrapService.accountEndpointRecords.exists(_.inject == expectedQueryInjects),
          BootstrapService.accountEndpointRecords.exists(_.inject == expectedBodyInjects),
          BootstrapService.accountEndpointRecords.forall(_.stepUpCondition.isEmpty),
          BootstrapService.accountEndpointRecords.forall(_.stepUpAcr.isEmpty),
          BootstrapService.accountEndpointRecords.forall(_.maxAge.isEmpty),
        )
      },
      test("session revocation is denied for the caller's own session") {
        assertTrue(
          BootstrapService.accountEndpointRecords
            .filter(endpoint => endpoint.method == "DELETE" && endpoint.path == "/settings/sessions")
            .map(_.allowExpression) == List(Some("token.sid != request.body.targetSessionId")),
          BootstrapService.accountEndpointRecords
            .filterNot(endpoint => endpoint.method == "DELETE" && endpoint.path == "/settings/sessions")
            .forall(_.allowExpression.isEmpty),
        )
      },
      // Reasserting authMethod on every boot is one-way safe (client_secret -> tls_client_auth
      // mints a secretless credential from a secret-bearing one); the other direction is not,
      // and is what these refuse rather than silently apply.
      test("refuses a boot that would downgrade central-admin off a method with no secret to mint") {
        assertTrue(
          BootstrapService.authMethodDowngradeRefusal(
            Some(centralAdminRecord(AuthMethod.tls_client_auth)),
            secretlessCredential,
          ).exists(_.contains("bootstrap.central-admin-mtls was removed")),
        )
      },
      test("does not refuse a boot that keeps or grants a secret-minting method") {
        assertTrue(
          BootstrapService.authMethodDowngradeRefusal(
            Some(centralAdminRecord(AuthMethod.client_secret)),
            secretlessCredential,
          ).isEmpty,
          BootstrapService.authMethodDowngradeRefusal(
            Some(centralAdminRecord(AuthMethod.tls_client_auth)),
            mtlsCredential,
          ).isEmpty,
        )
      },
      test("does not refuse a boot with no prior central-admin to downgrade") {
        assertTrue(BootstrapService.authMethodDowngradeRefusal(None, secretlessCredential).isEmpty)
      },
    // #424: the default tenant is FAPI 2.0, and private_key_jwt with DPoP-bound tokens is what
    // it admits for a service client with no redirect URIs.
    test("registers utils as private_key_jwt against its configured public key, over a secret also given") {
      val credential = BootstrapService.utilityClientCredential(
        utilitySeed(secret = Some(Secret(Array[Byte](1))), publicKeyJwk = Some(jwkOf(utilityKey.toPublicJWK))),
      )
      assertTrue(
        credential.map(_.authMethod) == Right(AuthMethod.private_key_jwt),
        credential.exists(_.secret.isEmpty),
        credential.exists(_.jwks.exists(_.publicKeys.isRight)),
        credential.exists(_.conformant),
      )
    },
    test("keeps utils on client_secret, outside the profile, without a public key") {
      val credential = BootstrapService.utilityClientCredential(utilitySeed(secret = Some(Secret(Array[Byte](1)))))
      assertTrue(
        credential.map(_.authMethod) == Right(AuthMethod.client_secret),
        credential.exists(_.secret.nonEmpty),
        credential.exists(!_.conformant),
      )
    },
    test("refuses to boot with neither a public key nor a secret for utils") {
      assertTrue(BootstrapService.utilityClientCredential(utilitySeed()).isLeft)
    },
    test("refuses to boot on a utils key that carries its private half") {
      assertTrue(
        BootstrapService.utilityClientCredential(utilitySeed(publicKeyJwk = Some(jwkOf(utilityKey))))
          .left.exists(_.contains("public keys only")),
      )
    },
    test("refuses a boot that would move utils back to client_secret when it holds no secret") {
      assertTrue(
        BootstrapService.utilityClientDowngradeRefusal(
          Some(centralAdminRecord(AuthMethod.private_key_jwt)),
          utilitySecretCredential,
        ).exists(_.contains("bootstrap.utility-client.public-key-jwk was removed")),
        BootstrapService.utilityClientDowngradeRefusal(
          Some(centralAdminRecord(AuthMethod.client_secret)),
          utilitySecretCredential,
        ).isEmpty,
        BootstrapService.utilityClientDowngradeRefusal(
          Some(centralAdminRecord(AuthMethod.client_secret)),
          utilityKeyCredential,
        ).isEmpty,
        BootstrapService.utilityClientDowngradeRefusal(None, utilitySecretCredential).isEmpty,
      )
    },
  )
