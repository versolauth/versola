package versola.central.configuration.clients

import versola.central.configuration.edges.{EdgeCertificate, EdgeGroupId}
import versola.central.configuration.partners.{PartnerContact, PartnerId, PartnerLimits, PartnerRecord, PartnerSettings, PartnerStatus}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.RedirectUri
import zio.*
import zio.test.*

import java.time.Instant

/** The partner client invariant (#549, #962 §2), rule by rule: what is refused outright, and what is outside the limits
  * and named field by field. */
object PartnerClientRulesSpec extends ZIOSpecDefault:

  private val clientId = ClientId("acme-shop")
  private val group = EdgeGroupId("acme")

  private val partner = PartnerRecord(
    id = PartnerId("acme"),
    tenantId = TenantId("default"),
    displayName = "ACME",
    logoUri = None,
    contact = PartnerContact("ops@acme.example", None),
    status = PartnerStatus.Active,
    limits = PartnerLimits(
      scopes = Set(ScopeToken("openid"), ScopeToken("phone")),
      redirectDomains = Set("acme.example", "Shop.ACME.app"),
      maxClients = 2,
      maxEdges = 2,
      accessTokenTtlMax = 600,
      refreshTokenTtlMax = 86400,
      clientAuthMethods = Set(AuthMethod.tls_client_auth, AuthMethod.private_key_jwt),
      rateLimitPolicy = "partner-default",
      resourceDomains = None,
    ),
    groupId = group,
    consoleClientId = ClientId("acme-console"),
    settings = PartnerSettings.empty,
    reviewAt = None,
    createdAt = Instant.EPOCH,
    version = 0L,
  )

  private val groupAuth = MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_uri, EdgeCertificate.groupUriFor(group))

  private val conforming = PartnerClientRules.Subject(
    applicationType = ApplicationType.web,
    kind = Some(ClientKind.web),
    consentFlow = true,
    authMethod = AuthMethod.tls_client_auth,
    mtlsAuth = Some(groupAuth),
    certificateBoundAccessTokens = false,
    redirectUris = Set(RedirectUri("https://www.acme.example/callback"), RedirectUri("https://shop.acme.app/cb")),
    scope = Set(ScopeToken("openid"), ScopeToken("phone")),
    accessTokenTtl = 600.seconds,
    refreshTokenTtl = 86400.seconds,
    backChannelLogoutUri = None,
  )

  private def invariant(subject: PartnerClientRules.Subject) = PartnerClientRules.invariant(clientId, partner, subject).map(_.reason)

  private def outside(subject: PartnerClientRules.Subject) = PartnerClientRules.outsideLimits(partner, subject).map(_.field)

  def spec = suite("PartnerClientRules")(
    suite("the invariant: what makes the client a partner's client at all")(
      test("a conforming web client passes, inside its limits") {
        assertTrue(invariant(conforming).isEmpty, outside(conforming).isEmpty)
      },
      test("never a service client") {
        assertTrue(invariant(conforming.copy(kind = Some(ClientKind.service))).exists(_.contains("never a service client")))
      },
      test("consent is required: the operator's users are told which partner they sign in to") {
        assertTrue(invariant(conforming.copy(consentFlow = false)).exists(_.contains("consentFlow")))
      },
      test("a shared secret or no credential never authenticates a partner client") {
        assertTrue(
          invariant(conforming.copy(authMethod = AuthMethod.client_secret, mtlsAuth = None)).exists(_.contains("client_secret")),
          invariant(conforming.copy(authMethod = AuthMethod.none, mtlsAuth = None)).exists(_.contains("none")),
          invariant(conforming.copy(authMethod = AuthMethod.self_signed_tls_client_auth, mtlsAuth = Some(MutualTlsAuth.SelfSignedTlsClientAuth())))
            .exists(_.contains("self_signed_tls_client_auth")),
        )
      },
      test("an allowed method the operator did not grant this partner is refused, naming the granted ones") {
        val tlsOnly = partner.copy(limits = partner.limits.copy(clientAuthMethods = Set(AuthMethod.tls_client_auth)))
        val refused = PartnerClientRules.invariant(clientId, tlsOnly, conforming.copy(authMethod = AuthMethod.private_key_jwt, mtlsAuth = None))
        assertTrue(refused.exists(_.reason.contains("limited to tls_client_auth")))
      },
      test("tls_client_auth is recognised by the group's certificate and nothing else") {
        val otherGroup = MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_uri, EdgeCertificate.groupUriFor(EdgeGroupId("globex")))
        val byDn = MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, "CN=acme-shop")
        assertTrue(
          invariant(conforming.copy(mtlsAuth = Some(otherGroup))).exists(_.contains("urn:versola:edge-group:acme")),
          invariant(conforming.copy(mtlsAuth = Some(byDn))).exists(_.contains("san_uri")),
          invariant(conforming.copy(mtlsAuth = None)).exists(_.contains("san_uri")),
        )
      },
      test("private_key_jwt needs no mtlsAuth") {
        assertTrue(invariant(conforming.copy(authMethod = AuthMethod.private_key_jwt, mtlsAuth = None)).isEmpty)
      },
      // a tls_client_auth client's tokens are bound already, by what it authenticates with; the flag is for a client with no group
      // certificate to re-bind through
      test("certificateBoundAccessTokens is refused for private_key_jwt, and redundant, so allowed, for tls_client_auth") {
        val keyed = conforming.copy(authMethod = AuthMethod.private_key_jwt, mtlsAuth = None)
        assertTrue(
          invariant(keyed.copy(certificateBoundAccessTokens = true)).exists(_.contains("certificateBoundAccessTokens")),
          invariant(conforming.copy(certificateBoundAccessTokens = true)).isEmpty,
        )
      },
    ),
    suite("the limits: what the operator may grant anyway")(
      test("a redirect URI that is not https, or not under one of the partner's domains, names redirectUris") {
        val http = conforming.copy(redirectUris = Set(RedirectUri("http://www.acme.example/callback")))
        val foreign = conforming.copy(redirectUris = Set(RedirectUri("https://acme.example.evil.test/callback")))
        val lookalike = conforming.copy(redirectUris = Set(RedirectUri("https://notacme.example/callback")))
        assertTrue(outside(http) == List("redirectUris"), outside(foreign) == List("redirectUris"), outside(lookalike) == List("redirectUris"))
      },
      test("the domain itself, a subdomain, and any letter case are under the domain") {
        val under = conforming.copy(redirectUris =
          Set(RedirectUri("https://acme.example/cb"), RedirectUri("https://A.B.ACME.EXAMPLE/cb"), RedirectUri("https://shop.acme.app/x")),
        )
        assertTrue(outside(under).isEmpty)
      },
      test("what is under the domain is read from the host the URI names: user info, a port, a trailing dot, an address, a look-alike") {
        def under(uri: String) = PartnerClientRules.underDomains(uri, Set("acme.example"))
        assertTrue(
          // the host is what follows the `@`: the user part is no domain
          !under("https://acme.example@evil.test/cb"),
          under("https://evil.test@acme.example/cb"),
          under("https://acme.example:8443/cb"),
          // a trailing dot, another spelling of the same name, is not the domain's own spelling
          !under("https://acme.example./cb"),
          !under("https://10.0.0.1/cb"),
          !under("https://[::1]/cb"),
          !under("https://127.1/cb"),
          !under("https://xn--acme-9ua.example/cb"),
          !under("https://acme.example.evil.test/cb"),
          !under("https://evilacme.example/cb"),
          !under("http://acme.example/cb"),
          !under("//acme.example/cb"),
        )
      },
      test("a back-channel logout address is the partner's own: https, a name under its domains, never an address or localhost") {
        def refused(uri: String) = invariant(conforming.copy(backChannelLogoutUri = Some(uri))).exists(_.contains("backChannelLogoutUri"))
        assertTrue(
          invariant(conforming.copy(backChannelLogoutUri = Some("https://shop.acme.example/logout"))).isEmpty,
          invariant(conforming.copy(backChannelLogoutUri = None)).isEmpty,
          refused("https://internal.example/logout"),
          refused("http://shop.acme.example/logout"),
          refused("https://10.1.2.3/logout"),
          refused("https://localhost/logout"),
          refused("https://169.254.169.254/latest/meta-data"),
          refused("https://[::1]:8443/logout"),
        )
      },
      test("a resource of a partner is https, under its resource domains (else its redirect domains), in the one spelling") {
        def violation(uri: String, p: PartnerRecord = partner) = PartnerClientRules.resourceUriViolation(p, uri)
        val own = partner.copy(limits = partner.limits.copy(resourceDomains = Some(Set("api.acme.example"))))
        assertTrue(
          violation("https://api.acme.example").isEmpty,
          violation("https://v2.api.acme.example").isEmpty,
          violation("https://acme.example").isEmpty,
          violation("https://acme.example:8443").isEmpty,
          violation("http://acme.example").isDefined,
          violation("https://operator.example").isDefined,
          violation("https://API.acme.example").exists(_.contains("lower case")),
          violation("https://acme.example:443").exists(_.contains("default port")),
          violation("https://10.0.0.1").isDefined,
          violation("https://localhost").isDefined,
          violation("https://acme.example./").isDefined,
          // its own resource domains replace the redirect domains for resources
          violation("https://acme.example", own).isDefined,
          violation("https://api.acme.example", own).isEmpty,
        )
      },
      test("a scope the partner was not granted names scope") {
        assertTrue(outside(conforming.copy(scope = Set(ScopeToken("openid"), ScopeToken("email")))) == List("scope"))
      },
      test("a lifetime above the partner's cap names the field, for access and refresh tokens alike") {
        assertTrue(
          outside(conforming.copy(accessTokenTtl = 601.seconds)) == List("accessTokenTtl"),
          outside(conforming.copy(refreshTokenTtl = 86401.seconds)) == List("refreshTokenTtl"),
        )
      },
      test("every violation is named, in a stable order") {
        val all = conforming.copy(
          redirectUris = Set(RedirectUri("https://other.test/cb")),
          scope = Set(ScopeToken("email")),
          accessTokenTtl = 7200.seconds,
          refreshTokenTtl = 864000.seconds,
        )
        assertTrue(
          outside(all) == List("redirectUris", "scope", "accessTokenTtl", "refreshTokenTtl"),
          PartnerClientRules.validateLimits(clientId, partner, all).exists(_.reason.contains("outside its limits")),
          PartnerClientRules.validateLimits(clientId, partner, conforming).isEmpty,
        )
      },
      test("the number of clients is a limit the operator cannot override by approving") {
        assertTrue(
          PartnerClientRules.validateClientCount(clientId, partner, existing = 1).isEmpty,
          PartnerClientRules.validateClientCount(clientId, partner, existing = 2).exists(_.reason.contains("at most 2")),
        )
      },
    ),
  )
