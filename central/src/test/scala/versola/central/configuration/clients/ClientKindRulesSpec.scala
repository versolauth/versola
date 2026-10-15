package versola.central.configuration.clients

import versola.central.configuration.CreateClientRequest
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.RedirectUri
import zio.test.*

object ClientKindRulesSpec extends ZIOSpecDefault:
  private val tenantId = TenantId("tenant-a")
  private val redirectUri = RedirectUri("https://example.com/callback")

  private val web = CreateWebClientRequest(
    tenantId = tenantId,
    id = ClientId("web"),
    clientName = Map("en" -> "Web"),
    redirectUris = Set(redirectUri),
    allowedScopes = Set(ScopeToken("read")),
    accessTokenTtl = 300,
    refreshTokenTtl = None,
    authFlow = None,
    authMethod = AuthMethod.client_secret,
    issueEdgeClientCertificate = false,
    enrollEdgeClientCertificate = false,
  )

  private val native = CreateNativeClientRequest(
    tenantId = tenantId,
    id = ClientId("native"),
    clientName = Map("en" -> "Native"),
    redirectUris = Set(redirectUri),
    allowedScopes = Set(ScopeToken("read")),
    accessTokenTtl = 300,
    refreshTokenTtl = None,
    authFlow = None,
    authMethod = AuthMethod.none,
    issueEdgeClientCertificate = false,
    enrollEdgeClientCertificate = false,
  )

  private val service = CreateServiceClientRequest(
    tenantId = tenantId,
    id = ClientId("service"),
    clientName = Map("en" -> "Service"),
    allowedScopes = Set(ScopeToken("read")),
    accessTokenTtl = 300,
    authMethod = AuthMethod.client_secret,
  )

  private def registration(kind: ClientKind, request: CreateClientRequest) =
    InvalidRegistrationConfiguration.validateKindRegistration(kind, request).map(_.reason)

  private def record(request: CreateClientRequest): OAuthClientRecord =
    OAuthClientRecord(
      id = request.id,
      tenantId = request.tenantId,
      clientName = request.clientName,
      redirectUris = request.redirectUris,
      scope = request.allowedScopes,
      secret = None,
      previousSecret = None,
      accessTokenTtl = zio.Duration.fromSeconds(300),
      refreshTokenTtl = zio.Duration.fromSeconds(300),
      permissions = Set.empty[Permission],
      theme = "default",
      authFlow = request.authFlow,
      otpTemplateId = "default",
      frontChannelLogoutUri = None,
      frontChannelLogoutSessionRequired = false,
      backChannelLogoutUri = None,
      logoUri = None,
      policyUri = None,
      tosUri = None,
      consentFlow = None,
      dpopBoundAccessTokens = request.dpopBoundAccessTokens,
      dpopSigningAlgs = Set.empty,
      dpopMinRsaKeySize = None,
      authMethod = request.authMethod,
      mtlsAuth = None,
      certificateBoundAccessTokens = request.certificateBoundAccessTokens,
      jwks = None,
      requireSignedRequestObject = request.requireSignedRequestObject,
      requirePushedAuthorizationRequests = request.requirePushedAuthorizationRequests,
      edgeSigningKey = None,
      edgeClientCertificate = None,
      template = request.template,
      createdAt = java.time.Instant.EPOCH,
      applicationType = request.applicationType.getOrElse(ApplicationType.web),
    )

  def spec = suite("typed client registration rules")(
    suite("web")(
      test("a confidential client with a redirect URI is admitted") {
        assertTrue(registration(ClientKind.web, web.toCreateClientRequest).isEmpty)
      },
      test("a public client is refused") {
        assertTrue(registration(ClientKind.web, web.copy(authMethod = AuthMethod.none).toCreateClientRequest).exists(_.contains("confidential")))
      },
      test("every confidential method is admitted") {
        val admitted = List(AuthMethod.private_key_jwt, AuthMethod.tls_client_auth, AuthMethod.self_signed_tls_client_auth)
        assertTrue(admitted.forall(method => registration(ClientKind.web, web.copy(authMethod = method).toCreateClientRequest).isEmpty))
      },
      test("no redirect URI is refused") {
        assertTrue(registration(ClientKind.web, web.copy(redirectUris = Set.empty).toCreateClientRequest).exists(_.contains("redirect URI")))
      },
      test("fronted by edge it cannot require a request object, and nothing else is written onto it") {
        val edge = web.copy(authMethod = AuthMethod.tls_client_auth, enrollEdgeClientCertificate = true)
        val written = edge.toCreateClientRequest
        assertTrue(
          registration(ClientKind.web, written).isEmpty,
          registration(
            ClientKind.web,
            edge.copy(requireSignedRequestObject = true).toCreateClientRequest,
          ).exists(_.contains("requireSignedRequestObject")),
          !written.certificateBoundAccessTokens,
          !written.requirePushedAuthorizationRequests,
        )
      },
    ),
    suite("native")(
      test("public and edge-fronted are the two methods") {
        assertTrue(
          registration(ClientKind.device, native.toCreateClientRequest).isEmpty,
          registration(
            ClientKind.device,
            native.copy(authMethod = AuthMethod.tls_client_auth, enrollEdgeClientCertificate = true).toCreateClientRequest,
          ).isEmpty,
          registration(
            ClientKind.device,
            native.copy(authMethod = AuthMethod.private_key_jwt).toCreateClientRequest,
          ).exists(_.contains("public (none) or fronted by edge")),
        )
      },
      test("a public one has no certificate to present") {
        assertTrue(registration(
          ClientKind.device,
          native.copy(enrollEdgeClientCertificate = true).toCreateClientRequest,
        ).exists(_.contains("not fronted by edge")))
      },
      test("the settings the kind fixes are fixed whatever the caller sent") {
        val fixed = native.copy(authMethod = AuthMethod.tls_client_auth, enrollEdgeClientCertificate = true).toCreateClientRequest
        assertTrue(
          fixed.dpopBoundAccessTokens,
          // Public, DPoP is the app's to ask for.
          !native.toCreateClientRequest.dpopBoundAccessTokens,
          native.copy(dpopBoundAccessTokens = Some(true)).toCreateClientRequest.dpopBoundAccessTokens,
          fixed.requirePushedAuthorizationRequests,
          !fixed.certificateBoundAccessTokens,
          !fixed.requireSignedRequestObject,
          fixed.applicationType == Some(ApplicationType.native),
          fixed.jwks.isEmpty,
          // A public app has no edge to push for it.
          !native.toCreateClientRequest.requirePushedAuthorizationRequests,
        )
      },
      test("no redirect URI is refused") {
        assertTrue(registration(ClientKind.device, native.copy(redirectUris = Set.empty).toCreateClientRequest).exists(_.contains("redirect URI")))
      },
    ),
    suite("service")(
      test("a client that authenticates is admitted") {
        assertTrue(registration(ClientKind.service, service.toCreateClientRequest).isEmpty)
      },
      test("one that does not is refused") {
        assertTrue(registration(
          ClientKind.service,
          service.copy(authMethod = AuthMethod.none).toCreateClientRequest,
        ).exists(_.contains("must authenticate")))
      },
      test("it has nothing a user signs in with") {
        val fixed = service.toCreateClientRequest
        assertTrue(
          fixed.redirectUris.isEmpty,
          fixed.authFlow.isEmpty,
          fixed.consentFlow.isEmpty,
          fixed.refreshTokenTtl.isEmpty,
          !fixed.requirePushedAuthorizationRequests,
          !fixed.requireSignedRequestObject,
          fixed.template == Some(ClientTemplate(ClientKind.service)),
        )
      },
    ),
    suite("what an update leaves fixed")(
      test("a device fronted by edge is bound to its key and its pushed request; a public one keeps its own") {
        val stored = record(native.toCreateClientRequest).copy(dpopBoundAccessTokens = false)
        val update = UpdateWebClientRequest.of(stored.id).toUpdateClientRequest
        val publicApp = InvalidRegistrationConfiguration.withKindFixed(ClientKind.device, update, stored, edgeFronted = false)
        val edgeApp = InvalidRegistrationConfiguration.withKindFixed(
          ClientKind.device,
          update,
          stored.copy(authMethod = AuthMethod.tls_client_auth),
          edgeFronted = true,
        )
        assertTrue(
          // A public app's DPoP is its own choice, so what the request says stands.
          publicApp.dpopBoundAccessTokens.isEmpty,
          publicApp.requirePushedAuthorizationRequests.isEmpty,
          edgeApp.dpopBoundAccessTokens == Some(true),
          edgeApp.requirePushedAuthorizationRequests == Some(true),
          edgeApp.certificateBoundAccessTokens == Some(false),
          edgeApp.requireSignedRequestObject == Some(false),
          InvalidRegistrationConfiguration.withKindFixed(ClientKind.web, update, stored, edgeFronted = false) == update,
        )
      },
    ),
    suite("a web client fronted by edge on update")(
      test("cannot start requiring a request object, and is otherwise left as it is") {
        val stored = record(web.copy(authMethod = AuthMethod.tls_client_auth).toCreateClientRequest)
        val update = UpdateWebClientRequest.of(stored.id)
        def check(request: UpdateWebClientRequest, edge: Boolean = true) =
          InvalidRegistrationConfiguration.validateKindUpdate(ClientKind.web, request.toUpdateClientRequest, stored, edgeFronted = edge)
        assertTrue(
          check(update).isEmpty,
          check(update.copy(requireSignedRequestObject = Some(true))).exists(_.reason.contains("requireSignedRequestObject")),
          check(update.copy(requireSignedRequestObject = Some(true)), edge = false).isEmpty,
          InvalidRegistrationConfiguration.withKindFixed(
            ClientKind.web,
            update.toUpdateClientRequest,
            stored,
            edgeFronted = true,
          ) == update.toUpdateClientRequest,
        )
      },
    ),
    suite("the kind of a stored client")(
      test("a template names it") {
        assertTrue(
          ClientKind.of(record(web.toCreateClientRequest)) == ClientKind.web,
          ClientKind.of(record(native.toCreateClientRequest)) == ClientKind.device,
          ClientKind.of(record(service.toCreateClientRequest)) == ClientKind.service,
        )
      },
      test("without one it is read off what the client is") {
        def untemplated(request: CreateClientRequest) = record(request).copy(template = None)
        assertTrue(
          ClientKind.of(untemplated(native.toCreateClientRequest)) == ClientKind.device,
          ClientKind.of(untemplated(web.toCreateClientRequest)) == ClientKind.web,
          ClientKind.of(untemplated(service.toCreateClientRequest)) == ClientKind.service,
        )
      },
    ),
  )
