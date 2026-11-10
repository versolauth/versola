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
    ),
    groupId = group,
    consoleClientId = ClientId("acme-console"),
    settings = PartnerSettings.empty,
    reviewAt = None,
    createdAt = Instant.EPOCH,
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
      test("tokens are never bound to one replica's certificate") {
        assertTrue(invariant(conforming.copy(certificateBoundAccessTokens = true)).exists(_.contains("certificateBoundAccessTokens")))
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
