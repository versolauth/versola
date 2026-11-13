package versola.central.configuration.clients.attestation

import versola.central.configuration.clients.*
import versola.central.configuration.tenants.TenantId
import versola.util.RedirectUri
import zio.*

import java.time.Instant

private[attestation] object AttestationFixtures:

  val digest = "ab" * 32

  val ios: IosAttestation = IosAttestation(appIds = Set("ABCDE12345.com.example.app"), environment = IosEnvironment.production)

  val android: AndroidApp = AndroidApp(packageName = "com.example.app", certificateDigests = Set(digest))

  val enforcing: ClientAttestation = ClientAttestation(mode = AttestationMode.enforce, ios = Some(ios), android = List(android))

  /** A client of the given kind: `native` with `tls_client_auth` is the only one edge fronts natively. */
  def client(id: ClientId, application: ApplicationType, method: AuthMethod): OAuthClientRecord = OAuthClientRecord(
    id = id,
    tenantId = TenantId("tenant-a"),
    clientName = Map("en" -> "App"),
    redirectUris = Set(RedirectUri("https://example.com/callback")),
    scope = Set.empty,
    secret = None,
    previousSecret = None,
    accessTokenTtl = 5.minutes,
    refreshTokenTtl = 7776000.seconds,
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
    authMethod = method,
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
    applicationType = application,
  )

  def nativeClient(id: ClientId): OAuthClientRecord = client(id, ApplicationType.native, AuthMethod.tls_client_auth)

  def webClient(id: ClientId): OAuthClientRecord = client(id, ApplicationType.web, AuthMethod.client_secret)
