package versola

import com.nimbusds.jose.jwk.gen.{ECKeyGenerator, RSAKeyGenerator}
import com.nimbusds.jose.jwk.{Curve, JWK, KeyUse}
import com.nimbusds.jose.{JWEAlgorithm, JWSAlgorithm}
import org.scalamock.stubs.ZIOStubs
import versola.central.CentralConfig
import versola.central.configuration.challenges.ChallengeSettingsService
import versola.central.configuration.clients.ApplicationType
import versola.central.configuration.clients.{
  AuthFactor,
  AuthFactorType,
  AuthMethod,
  ClientId,
  MutualTlsAuth,
  MutualTlsSubjectType,
  OAuthClientRecord,
  OAuthClientService,
}
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.forms.FormPreviews
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.themes.{ThemeRecord, ThemeRepository}
import versola.central.configuration.{InjectRule, InjectTarget}
import versola.util.http.DeclaredRateLimitBy
import versola.util.http.{DeclaredRateLimit, GuardMode, RateLimitWindow}
import versola.util.theme.{ThemeOverrides, ThemeTokens}
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
    CentralConfig.BootstrapConfig.CentralAdminMtlsSeed(Some(certificate), "ssl-client-cert", "urlEncodedPem")

  /** The stored `central-admin` a previous boot left behind, varying only the one column
    * [[BootstrapService.authMethodMismatch]] reads. */
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
      themeOverrides = None,
      applicationType = ApplicationType.web,
    )

  private val utilityKey = ECKeyGenerator(Curve.P_256).keyID("utils-1").algorithm(JWSAlgorithm.ES256).generate()

  private def jwkOf(key: JWK): Json.Obj = key.toJSONString.fromJson[Json.Obj].toOption.get

  private def utilitySeed(secret: Option[Secret] = None, publicKeyJwk: Option[Json.Obj] = None) =
    CentralConfig.BootstrapConfig.UtilityClientSeed(ClientId("utils"), secret, publicKeyJwk)

  def spec = suite("BootstrapService")(
    test("starts a tenant with an otp class that asks for a one-time code and a passkey class that asks for a passkey") {
      val settings = BootstrapService.defaultChallengeSettings(
        versola.central.configuration.tenants.TenantId("default"),
        versola.central.CentralConfig.PasskeyConfig("localhost", List("http://localhost")),
      )
      assertTrue(settings.acrVocabulary == Some(Map("otp" -> List("otp"), "passkey" -> List("passkey"))))
    },
    test("starts a tenant with no post-logout address: the deployment's own comes from the seeded presets, not from the code") {
      val settings = BootstrapService.defaultChallengeSettings(
        versola.central.configuration.tenants.TenantId("default"),
        versola.central.CentralConfig.PasskeyConfig("localhost", List("http://localhost")),
      )
      assertTrue(settings.postLogoutRedirectUris.isEmpty)
    },
    test("names each seeded locale in its own language, as the login forms' language selector shows it") {
      assertTrue(
        BootstrapService.defaultLocales.map(locale => locale.code -> locale.name).toMap ==
          Map("en" -> "English", "ru" -> "Русский"),
      )
    },
    test("seeds default as a visual theme on the versola base with no CSS when it is absent") {
      val themes = stub[ThemeRepository]
      for
        _ <- themes.getAll.succeedsWith(Vector.empty)
        _ <- themes.create.succeedsWith(())
        _ <- BootstrapService.seedDefaultTheme(themes)
        created = themes.create.calls
        updates = themes.update.times
      yield assertTrue(
        created == List(ThemeRecord("default", "", None, Some(ThemeTokens(base = "versola", overrides = None, variables = None)))),
        updates == 0,
      )
    },
    test("converts a stylesheet default (tokens NULL) to the visual theme, keeping its tenant") {
      val themes = stub[ThemeRepository]
      for
        _ <- themes.getAll.succeedsWith(Vector(ThemeRecord("default", ":root{--brand:red}", None, tokens = None)))
        _ <- themes.update.succeedsWith(())
        _ <- BootstrapService.seedDefaultTheme(themes)
        updated = themes.update.calls
        creates = themes.create.times
      yield assertTrue(
        updated == List(ThemeRecord("default", "", None, Some(ThemeTokens(base = "versola", overrides = None, variables = None)))),
        creates == 0,
      )
    },
    test("leaves a token default alone: the administrator may have changed its base or overrides") {
      val themes = stub[ThemeRepository]
      val custom = ThemeRecord(
        "default",
        "a{}",
        None,
        Some(ThemeTokens(
          base = "versola",
          overrides = Some(ThemeOverrides.empty.copy(shape = Some(versola.util.theme.Shape.sharp))),
          variables = None,
        )),
      )
      for
        _ <- themes.getAll.succeedsWith(Vector(custom))
        _ <- BootstrapService.seedDefaultTheme(themes)
        creates = themes.create.times
        updates = themes.update.times
      yield assertTrue(creates == 0, updates == 0)
    },
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
    test("has central issue central-admin's tls_client_auth certificate when the seed carries none") {
      val credential = BootstrapService.centralAdminCredential(
        Some(CentralConfig.BootstrapConfig.CentralAdminMtlsSeed(None, "ssl-client-cert", "urlEncodedPem")),
      )
      assertTrue(
        credential.map(_.authMethod) == Right(AuthMethod.tls_client_auth),
        credential.map(_.mtlsAuth) == Right(None),
        credential.map(_.edgeClientCertificate) == Right(None),
        credential.map(_.issueEdgeClientCertificate) == Right(true),
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
    test("the edge policies API is reachable from the console: registered as endpoints, read with edges:read and changed with edges:manage") {
      val prod = BootstrapService.centralEndpoints(EnvName.Prod)
      def grantedBy(permission: String) =
        BootstrapService.permissionCatalog.find(
          _._1 == versola.central.configuration.permissions.Permission(permission),
        ).map(_._3).getOrElse(Set.empty)
      assertTrue(
        prod.contains("GET" -> "/configuration/edges/policies"),
        prod.contains("PATCH" -> "/configuration/edges/policies"),
        prod.contains("GET" -> "/configuration/edges/rate-limits/recommended"),
        grantedBy("edges:read").contains(endpointId("GET", "/configuration/edges/policies")),
        grantedBy("edges:read").contains(endpointId("GET", "/configuration/edges/rate-limits/recommended")),
        grantedBy("edges:manage").contains(endpointId("PATCH", "/configuration/edges/policies")),
        // reading is not changing: edges:read must not carry the patch
        !grantedBy("edges:read").contains(endpointId("PATCH", "/configuration/edges/policies")),
      )
    },
    test("every endpoint the edge permissions grant is registered as an endpoint of the central resource") {
      val registered = BootstrapService.centralEndpoints(EnvName.Prod).toSet
      val granted = BootstrapService.permissionCatalog
        .filter(entry => Set("edges:read", "edges:manage").contains(entry._1.toString))
        .flatMap(_._3)
      val missing = granted.filterNot(id => registered.exists((method, path) => endpointId(method, path) == id))
      assertTrue(granted.nonEmpty, missing.isEmpty)
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
    test(
      "central's antifraud endpoints are registered in production, and the ones that change something are told who the admin is from the verified token",
    ) {
      val prod = BootstrapService.centralEndpoints(EnvName.Prod)
      val antifraud = prod.filter((_, path) => path.startsWith("/antifraud/"))
      // what the controllers read from the body or the query; the actor headers have a test of their own
      def injected(method: String, path: String) =
        BootstrapService.centralInjects(method, path).filterNot(_.name.startsWith("actor")).map(r => (r.target, r.name, r.expression))
      val user = (method: String) => (if method == "GET" then InjectTarget.query else InjectTarget.body, "userId", "token.sub")
      assertTrue(
        antifraud.size == 17,
        antifraud.toSet == Set(
          "GET" -> "/antifraud/lists",
          "POST" -> "/antifraud/lists",
          "DELETE" -> "/antifraud/lists",
          "POST" -> "/antifraud/lists/preview",
          "GET" -> "/antifraud/lists/audit",
          "GET" -> "/antifraud/global/lists",
          "POST" -> "/antifraud/global/lists",
          "DELETE" -> "/antifraud/global/lists",
          "POST" -> "/antifraud/global/lists/preview",
          "GET" -> "/antifraud/global/lists/audit",
          "GET" -> "/antifraud/settings",
          "PUT" -> "/antifraud/settings",
          "PATCH" -> "/antifraud/settings",
          "DELETE" -> "/antifraud/settings",
          "POST" -> "/antifraud/counters/reset",
          "GET" -> "/antifraud/rules",
          "GET" -> "/antifraud/status",
        ),
        // a change names the admin, a read names nobody
        injected("POST", "/antifraud/global/lists") == Vector(user("POST")),
        injected("DELETE", "/antifraud/global/lists") == Vector(user("DELETE")),
        injected("POST", "/antifraud/global/lists/preview") == Vector(user("POST")),
        injected("PUT", "/antifraud/settings") == Vector(user("PUT")),
        injected("PATCH", "/antifraud/settings") == Vector(user("PATCH")),
        injected("DELETE", "/antifraud/settings") == Vector(user("DELETE")),
        injected("POST", "/antifraud/counters/reset") == Vector(user("POST")),
        injected("GET", "/antifraud/global/lists").isEmpty,
        injected("GET", "/antifraud/global/lists/audit").isEmpty,
        injected("GET", "/antifraud/settings").isEmpty,
        injected("GET", "/antifraud/rules").isEmpty,
        injected("GET", "/antifraud/status").isEmpty,
        injected("GET", "/users").isEmpty,
      )
    },
    test("the tenant routes of the lists are told the tenant of the token as well, reads included, and the operator routes are not") {
      def injected(method: String, path: String) =
        BootstrapService.centralInjects(method, path).filterNot(_.name.startsWith("actor")).map(r => (r.target, r.name, r.expression)).toSet
      val tenant = (method: String) => (if method == "GET" then InjectTarget.query else InjectTarget.body, "callerTenantId", "token.tenant_id")
      val user = (method: String) => (if method == "GET" then InjectTarget.query else InjectTarget.body, "userId", "token.sub")
      val tenantRoutes = List(
        "POST" -> "/antifraud/lists",
        "DELETE" -> "/antifraud/lists",
        "POST" -> "/antifraud/lists/preview",
        "GET" -> "/antifraud/lists",
        "GET" -> "/antifraud/lists/audit",
      )
      val operatorRoutes = List(
        "POST" -> "/antifraud/global/lists",
        "DELETE" -> "/antifraud/global/lists",
        "POST" -> "/antifraud/global/lists/preview",
        "GET" -> "/antifraud/global/lists",
        "GET" -> "/antifraud/global/lists/audit",
        "PUT" -> "/antifraud/settings",
        "PATCH" -> "/antifraud/settings",
        "DELETE" -> "/antifraud/settings",
        "POST" -> "/antifraud/counters/reset",
      )
      assertTrue(
        tenantRoutes.forall((method, path) => injected(method, path).contains(tenant(method))),
        // a change is told the admin too, a read is not
        tenantRoutes.filter(_._1 != "GET").forall((method, path) => injected(method, path) == Set(tenant(method), user(method))),
        tenantRoutes.filter(_._1 == "GET").forall((method, path) => injected(method, path) == Set(tenant(method))),
        operatorRoutes.forall((method, path) => !injected(method, path).exists(_._2 == "callerTenantId")),
      )
    },
    test(
      "antifraud:manage is the tenant's lists, antifraud:operate the global lists, the settings writes and the counters, and antifraud:read the reads",
    ) {
      def endpoints(permission: String) =
        BootstrapService.permissionCatalog.find(
          _._1 == versola.central.configuration.permissions.Permission(permission),
        ).map(_._3).getOrElse(Set.empty)
      def id(method: String, path: String) = endpointId(method, path)
      val operate = endpoints("antifraud:operate")
      val manage = endpoints("antifraud:manage")
      val read = endpoints("antifraud:read")
      assertTrue(
        manage == Set(id("POST", "/antifraud/lists"), id("POST", "/antifraud/lists/preview"), id("DELETE", "/antifraud/lists")),
        operate == Set(
          id("GET", "/antifraud/global/lists"),
          id("POST", "/antifraud/global/lists"),
          id("DELETE", "/antifraud/global/lists"),
          id("POST", "/antifraud/global/lists/preview"),
          id("GET", "/antifraud/global/lists/audit"),
          id("PUT", "/antifraud/settings"),
          id("PATCH", "/antifraud/settings"),
          id("DELETE", "/antifraud/settings"),
          id("POST", "/antifraud/counters/reset"),
        ),
        read == Set(
          id("GET", "/antifraud/lists"),
          id("GET", "/antifraud/lists/audit"),
          id("GET", "/antifraud/settings"),
          id("GET", "/antifraud/rules"),
          id("GET", "/antifraud/status"),
        ),
        // every endpoint of the catalogue belongs to one permission
        (operate.intersect(manage)).isEmpty,
        (operate.intersect(read)).isEmpty,
        (manage.intersect(read)).isEmpty,
        BootstrapService.centralEndpoints(EnvName.Prod).filter(_._2.startsWith("/antifraud/")).map((method, path) =>
          id(method, path),
        ).toSet == operate ++ manage ++ read,
      )
    },
    test("antifraud:operate is seeded into the administrator role only, and the security officer manages a tenant's lists, not the deployment's") {
      def holders(permission: String) =
        BootstrapService.roleCatalog.collect {
          case (role, _, permissions) if permissions.contains(versola.central.configuration.permissions.Permission(permission)) => role.toString
        }.toSet
      assertTrue(
        holders("antifraud:operate") == Set("oauth-admin"),
        holders("antifraud:manage") == Set("oauth-admin", "security"),
        holders("antifraud:read").contains("oauth-admin"),
        holders("antifraud:read").contains("security"),
        !holders("antifraud:read").contains("viewer"),
      )
    },
    test("every endpoint central serves to the console is told who the caller is by query parameters, and the service endpoints are not") {
      val params = BootstrapService.actorInjects.map(r => (r.target, r.name, r.expression))
      def injected(method: String, path: String) =
        BootstrapService.centralInjects(method, path).filter(_.name.startsWith("actor")).map(r => (r.target, r.name, r.expression))
      val prod = BootstrapService.centralEndpoints(EnvName.Prod)
      val service = BootstrapService.centralEndpoints(EnvName.Test("local")).filter(_._2.startsWith("/service/"))
      assertTrue(
        params == Vector(
          (InjectTarget.query, "actorId", "token.sub"),
          (InjectTarget.query, "actorTenantId", "token.tenant_id"),
          (InjectTarget.query, "actorClientId", "token.client_id"),
          // a token without a session sends none: the rule evaluates to nothing and edge does not forward it
          (InjectTarget.query, "actorSessionId", "has(token.sid) ? token.sid : ''"),
          // an edge that finds no address for the caller (or has none to offer) sends none
          (InjectTarget.query, "actorIp", "has(request.clientIp) ? request.clientIp : ''"),
        ),
        prod.nonEmpty,
        // reads and changes alike, a call without a body as well as one with: a query parameter needs neither
        prod.forall((method, path) => injected(method, path) == params),
        // no header is injected
        prod.forall((method, path) => BootstrapService.centralInjects(method, path).forall(_.target != InjectTarget.header)),
        service.nonEmpty,
        service.forall((method, path) => BootstrapService.centralInjects(method, path).isEmpty),
        // the user routes keep `userId` for the target: nothing here is injected into a body, and the names are the actor's own
        BootstrapService.centralInjects("POST", "/users/password/reset") == BootstrapService.actorInjects,
        BootstrapService.centralInjects("DELETE", "/configuration/clients") == BootstrapService.actorInjects,
        prod.contains("GET" -> "/users/audit"),
      )
    },
    test(
      "audit:read is the report, seeded to the administrator, security officer and auditor, and not to the roles that must not read people's trails",
    ) {
      def endpoints(permission: String) =
        BootstrapService.permissionCatalog.find(_._1 == versola.central.configuration.permissions.Permission(permission)).map(_._3)
      def holders(permission: String) =
        BootstrapService.roleCatalog.collect {
          case (role, _, permissions) if permissions.contains(versola.central.configuration.permissions.Permission(permission)) => role.toString
        }.toSet
      assertTrue(
        endpoints("audit:read").contains(Set(endpointId("GET", "/users/audit"))),
        // reserved: nothing is managed yet
        endpoints("audit:manage").contains(Set.empty),
        holders("audit:read") == Set("oauth-admin", "security", "auditor"),
        holders("audit:manage") == Set("oauth-admin"),
        // the path is under /users, but the endpoint is its own: no other permission grants a look at the trail (edge matches a path to
        // an endpoint segment by segment, not by prefix)
        BootstrapService.permissionCatalog.filter(_._3.contains(endpointId("GET", "/users/audit"))).map(_._1.toString) == List("audit:read"),
      )
    },
    test("a deployment that registered the endpoints with older inject rules gets the current ones, and nothing but the inject rules is rewritten") {
      def record(method: String, path: String, inject: Vector[InjectRule], allow: Option[String] = None) =
        versola.central.configuration.resources.ResourceEndpointRecord(
          id = endpointId(method, path),
          path = path,
          method = method,
          fetchUserInfo = false,
          allowExpression = allow,
          inject = inject,
          stepUpCondition = None,
          stepUpAcr = None,
          maxAge = None,
          rateLimit = None,
        )
      val user = InjectRule(InjectTarget.body, "userId", "token.sub")
      val customHeader = InjectRule(InjectTarget.header, "X-Custom", "token.sub")
      val custom = InjectRule(InjectTarget.query, "custom", "token.sub")
      def wanted(method: String, path: String) = record(method, path, BootstrapService.centralInjects(method, path))
      val catalogue =
        List(
          wanted("POST", "/antifraud/lists"),
          wanted("GET", "/antifraud/lists"),
          wanted("GET", "/users"),
          wanted("PUT", "/antifraud/settings"),
          wanted("POST", "/users"),
          wanted("DELETE", "/users"),
        )
      // as an earlier release left them: a tenant route with the user only, one with a rule the code no longer has, a custom allow
      // rule on another, an operator's own rule on a user route, and one that is current
      val existing = Vector(
        record("POST", "/antifraud/lists", Vector(user), allow = Some("token.sub != ''")),
        record("GET", "/antifraud/lists", Vector(InjectRule(InjectTarget.query, "userId", "token.sub"))),
        record("PUT", "/antifraud/settings", Vector(user)),
        record("GET", "/users", Vector(custom)),
        record("POST", "/users", BootstrapService.actorInjects),
        // an operator's own header rule on a route that has no actor rules yet
        record("DELETE", "/users", Vector(customHeader)),
        record("GET", "/antifraud/gone", Vector.empty),
      )
      val drifted = BootstrapService.injectDrift(existing, catalogue)
      def of(method: String, path: String) = drifted.find(e => e.method == method && e.path == path)
      assertTrue(
        drifted.map(e => (e.method, e.path)).toSet == Set(
          "POST" -> "/antifraud/lists",
          "GET" -> "/antifraud/lists",
          "PUT" -> "/antifraud/settings",
          "GET" -> "/users",
          "DELETE" -> "/users",
        ),
        // the antifraud endpoints have exactly the current rules, as they always had
        drifted.filter(_.path.startsWith("/antifraud/")).forall(e => e.inject == BootstrapService.centralInjects(e.method, e.path)),
        // another endpoint keeps what an operator added and gains the code's rules
        of("GET", "/users").exists(e => e.inject.toSet == (BootstrapService.actorInjects :+ custom).toSet),
        // the actor rules are in, and the operator's own header stays
        of("DELETE", "/users").exists(e => e.inject.toSet == (BootstrapService.actorInjects :+ customHeader).toSet),
        // the rest of the record is the operator's: the custom allow rule stays
        of("POST", "/antifraud/lists").flatMap(_.allowExpression).contains("token.sub != ''"),
        // a second pass finds nothing to do
        BootstrapService.injectDrift(
          drifted ++ existing.filter(e => e.path == "/antifraud/gone" || e.path == "/users" && e.method == "POST"),
          catalogue,
        ).isEmpty,
      )
    },
    test("offline_access is described without the word refresh, which users do not know") {
      val description = BootstrapService.scopeDescriptions(ScopeToken("offline_access"))
      assertTrue(
        description == Map("en" -> "Long-lived access", "ru" -> "Длительный доступ"),
      )
    },
    test("the consent previews describe the standard scopes exactly as bootstrap seeds them") {
      val matches =
        for
          form <- FormPreviews.all if form.formId == "consent"
          scenario <- form.scenarios
          scope <- scenario.step.asObject.flatMap(_.get("scopes")).flatMap(_.asArray).toList.flatMap(_.toList)
        yield
          val token = scope.asObject.flatMap(_.get("scope")).flatMap(_.asString).map(ScopeToken(_))
          val localizations = scope.asObject
            .flatMap(_.get("descriptionLocalizations"))
            .flatMap(_.asObject)
            .toList
            .flatMap(_.fields.collect { case (language, Json.Str(text)) => language -> text })
            .toMap
          token.flatMap(BootstrapService.scopeDescriptions.get).contains(localizations)
      assertTrue(matches.nonEmpty, matches.forall(identity))
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
          endpointId("PATCH", "/settings/password"),
        ),
      )
    },
    test("account endpoints inject trusted caller context") {
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
      )
    },
    test("only the password change is told which session it came from, and an absent sid is sent as empty") {
      val withSession = BootstrapService.accountEndpointRecords.filter: endpoint =>
        endpoint.inject.contains(InjectRule(InjectTarget.body, "sessionId", "has(token.sid) ? token.sid : ''"))
      assertTrue(
        withSession.map(endpoint => endpoint.method -> endpoint.path) == List("PATCH" -> "/settings/password"),
        // Every other body-injecting endpoint is exactly the caller context, nothing more.
        BootstrapService.accountEndpointRecords
          .filter(endpoint => endpoint.method != "GET" && endpoint.path != "/settings/password")
          .forall(_.inject.map(_.name) == Vector("userId", "clientId")),
      )
    },
    test("removing a passkey, starting to add one and signing a session out ask for a fresh sensitive sign-in") {
      val asking = BootstrapService.accountEndpointRecords.filter(_.stepUpAcr.isDefined)
      assertTrue(
        asking.map(endpoint => endpoint.method -> endpoint.path).toSet == Set(
          "DELETE" -> "/settings/sessions",
          "DELETE" -> "/settings/passkeys",
          "POST" -> "/settings/passkeys/register/start",
        ),
        asking.forall(_.stepUpCondition.contains("true")),
        asking.forall(_.stepUpAcr.contains("sensitive")),
        asking.forall(_.maxAge.contains(300)),
      )
    },
    test("a resource that already exists keeps the step-up it has, so an upgrade changes nothing for it") {
      val seeded = BootstrapService.accountEndpointRecords
      val running = seeded.map(_.copy(stepUpCondition = None, stepUpAcr = None, maxAge = None))
      val tuned = seeded.map(endpoint =>
        endpoint.copy(stepUpCondition = Some("request.body.amount > 1"), stepUpAcr = Some("mfa"), maxAge = Some(60)),
      )
      assertTrue(
        BootstrapService.mergeAccountEndpoints(seeded, running.map(endpoint => endpoint.id -> endpoint).toMap) == running,
        BootstrapService.mergeAccountEndpoints(seeded, tuned.map(endpoint => endpoint.id -> endpoint).toMap) == tuned,
      )
    },
    test("a rate limit an operator put on an account endpoint is kept when bootstrap converges the catalog") {
      val seeded = BootstrapService.accountEndpointRecords
      val limit = DeclaredRateLimit(
        limits = List(RateLimitWindow(limit = 30, windowSeconds = 60)),
        burst = 5,
        mode = GuardMode.Enforce,
        by = DeclaredRateLimitBy.None,
      )
      val tuned = seeded.map(endpoint => endpoint.copy(rateLimit = Some(limit)))
      assertTrue(BootstrapService.mergeAccountEndpoints(seeded, tuned.map(endpoint => endpoint.id -> endpoint).toMap) == tuned)
    },
    test("an endpoint a running resource does not have yet arrives with the seeded step-up") {
      val seeded = BootstrapService.accountEndpointRecords
      val withoutDelete = seeded.filterNot(endpoint => endpoint.method == "DELETE" && endpoint.path == "/settings/passkeys")
      val merged = BootstrapService.mergeAccountEndpoints(seeded, withoutDelete.map(endpoint => endpoint.id -> endpoint).toMap)
      assertTrue(merged == seeded)
    },
    test("reading the page, renaming a passkey, finishing an enrollment and changing the password ask for nothing more") {
      val quiet = BootstrapService.accountEndpointRecords.filter(_.stepUpAcr.isEmpty)
      assertTrue(
        quiet.map(endpoint => endpoint.method -> endpoint.path).toSet == Set(
          "GET" -> "/settings",
          "PATCH" -> "/settings/passkeys",
          "POST" -> "/settings/passkeys/register/finish",
          "PATCH" -> "/settings/password",
        ),
        quiet.forall(_.stepUpCondition.isEmpty),
        quiet.forall(_.maxAge.isEmpty),
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
    // A client's method is fixed when it is created, so a boot whose configuration calls for
    // another one refuses -- in either direction -- rather than apply it or carry on unchanged.
    test("refuses a boot whose configuration calls for another method than the client holds") {
      assertTrue(
        BootstrapService.authMethodMismatch(
          Some(centralAdminRecord(AuthMethod.tls_client_auth)),
          AuthMethod.client_secret,
          "bootstrap.central-admin-mtls",
        ).exists(reason =>
          reason.contains("'central-admin' is registered with tls_client_auth") &&
            reason.contains("bootstrap.central-admin-mtls calls for client_secret") &&
            reason.contains("fixed when it is created"),
        ),
        BootstrapService.authMethodMismatch(
          Some(centralAdminRecord(AuthMethod.client_secret)),
          AuthMethod.tls_client_auth,
          "bootstrap.central-admin-mtls",
        ).exists(_.contains("registered with client_secret")),
        BootstrapService.authMethodMismatch(
          Some(centralAdminRecord(AuthMethod.private_key_jwt)),
          AuthMethod.client_secret,
          "bootstrap.utility-client",
        ).exists(_.contains("bootstrap.utility-client calls for client_secret")),
      )
    },
    test("does not refuse a boot that calls for the method the client already holds") {
      assertTrue(
        BootstrapService.authMethodMismatch(
          Some(centralAdminRecord(AuthMethod.client_secret)),
          AuthMethod.client_secret,
          "bootstrap.central-admin-mtls",
        ).isEmpty,
        BootstrapService.authMethodMismatch(
          Some(centralAdminRecord(AuthMethod.tls_client_auth)),
          AuthMethod.tls_client_auth,
          "bootstrap.central-admin-mtls",
        ).isEmpty,
      )
    },
    test("does not refuse a boot with no prior client to hold a method") {
      assertTrue(
        BootstrapService.authMethodMismatch(None, AuthMethod.client_secret, "bootstrap.central-admin-mtls").isEmpty,
      )
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
    test("accepts an RSA public key for a seeded edge") {
      val key = RSAKeyGenerator(2048).keyID("edge-1").generate()
      assertTrue(BootstrapService.validateEdgeSeedKey(CentralConfig.BootstrapConfig.EdgeSeed(EdgeId("edge-1"), jwkOf(key.toPublicJWK))).isRight)
    },
    test("accepts an RSA edge key meant for encryption (use enc, alg RSA-OAEP-256)") {
      val key = RSAKeyGenerator(2048).keyID("edge-1").keyUse(KeyUse.ENCRYPTION).algorithm(JWEAlgorithm.RSA_OAEP_256).generate()
      assertTrue(BootstrapService.validateEdgeSeedKey(CentralConfig.BootstrapConfig.EdgeSeed(EdgeId("edge-1"), jwkOf(key.toPublicJWK))).isRight)
    },
    test("refuses a seeded edge key without kty or modulus") {
      val seed = (j: Json.Obj) => CentralConfig.BootstrapConfig.EdgeSeed(EdgeId("edge-1"), j)
      assertTrue(
        BootstrapService.validateEdgeSeedKey(seed(Json.Obj("kid" -> Json.Str("x")))).isLeft,
        BootstrapService.validateEdgeSeedKey(seed(Json.Obj("kty" -> Json.Str("RSA"), "e" -> Json.Str("AQAB")))).isLeft,
      )
    },
    test("refuses an EC public key for a seeded edge: the sync is RSA-encrypted") {
      val result = BootstrapService.validateEdgeSeedKey(CentralConfig.BootstrapConfig.EdgeSeed(EdgeId("edge-1"), jwkOf(utilityKey.toPublicJWK)))
      assertTrue(result.left.exists(_.contains("must be an RSA key")))
    },
    test("still boots a seeded edge key that carries its private half, and reports the members") {
      val key = RSAKeyGenerator(2048).keyID("edge-1").generate()
      val seed = CentralConfig.BootstrapConfig.EdgeSeed(EdgeId("edge-1"), jwkOf(key))
      assertTrue(BootstrapService.validateEdgeSeedKey(seed).isRight, BootstrapService.edgeSeedPrivateMembers(seed).contains("d"))
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
  )
