package versola.central.configuration.clients

import versola.central.configuration.*
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.{ClientAssertion, Dpop, JsonWebKeySet, Patch, PrivateClientCertificate, PrivateJsonWebKey, RedirectUri}
import zio.json.{JsonCodec, jsonNoExtraFields}
import zio.schema.*

// One registration and one update per kind of client (`POST`/`PUT /configuration/clients/{web,native,service}`).
//
// The two edge-certificate members are not defaulted, for the reason `CreateClientRequestJsonSpec`
// states: a caller registering a client names the certificate mode it chose.
//
// A member a kind does not have is refused rather than ignored: a native client asked to set
// `dpopBoundAccessTokens: false` is a mistake to report, not a value to drop.
//
// A request names only what its kind lets the caller choose. What a kind fixes is not a field at
// all, so it cannot be sent wrong: a native client's `applicationType`, its DPoP binding and its
// PAR requirement are set here, not read from the body. The general `CreateClientRequest` and
// `UpdateClientRequest` stay as they are for callers that predate these.

/** A site with a backend, where users sign in and tokens stay on the server. Confidential: it
  * authenticates with a secret, a key or a certificate, never as a public client. */
@jsonNoExtraFields
case class CreateWebClientRequest(
    tenantId: TenantId,
    id: ClientId,
    clientName: Map[String, String],
    redirectUris: Set[RedirectUri],
    allowedScopes: Set[ScopeToken],
    permissions: Set[Permission] = Set.empty,
    accessTokenTtl: Int,
    refreshTokenTtl: Option[Int],
    theme: String = "default",
    authFlow: Option[AuthFlow],
    registrationFlow: Option[RegistrationFlow] = None,
    otpTemplateId: String = "default",
    frontChannelLogoutUri: Option[String] = None,
    frontChannelLogoutSessionRequired: Boolean = false,
    backChannelLogoutUri: Option[String] = None,
    logoUri: Option[String] = None,
    policyUri: Option[String] = None,
    tosUri: Option[String] = None,
    consentFlow: Option[ConsentFlowDto] = None,
    dpopBoundAccessTokens: Boolean = false,
    dpopSigningAlgs: Set[Dpop.Algorithm] = Set.empty,
    dpopMinRsaKeySize: Option[Int] = None,
    authMethod: AuthMethod,
    mtlsAuth: Option[MutualTlsAuth] = None,
    certificateBoundAccessTokens: Boolean = false,
    jwks: Option[JsonWebKeySet] = None,
    generateJwks: Option[ClientAssertion.Algorithm] = None,
    requireSignedRequestObject: Boolean = false,
    requirePushedAuthorizationRequests: Boolean = false,
    edgeSigningKey: Option[PrivateJsonWebKey] = None,
    edgeClientCertificate: Option[PrivateClientCertificate] = None,
    issueEdgeClientCertificate: Boolean,
    enrollEdgeClientCertificate: Boolean,
) derives Schema, JsonCodec:
  /** Fronted by edge, edge's certificate is what binds the tokens and its pushed request is what is
    * trusted: both are written on rather than asked for, since a flag left at its default cannot be told
    * from one asked off. The opposite -- DPoP, a signed request object -- is refused by
    * [[InvalidRegistrationConfiguration.validateKindRegistration]]. */
  private def edgeFronted: Boolean =
    issueEdgeClientCertificate || enrollEdgeClientCertificate || edgeClientCertificate.nonEmpty

  def toCreateClientRequest: CreateClientRequest =
    CreateClientRequest(
      tenantId,
      id,
      clientName,
      redirectUris,
      allowedScopes,
      permissions,
      accessTokenTtl,
      refreshTokenTtl,
      theme,
      authFlow,
      registrationFlow,
      otpTemplateId,
      frontChannelLogoutUri,
      frontChannelLogoutSessionRequired,
      backChannelLogoutUri,
      logoUri,
      policyUri,
      tosUri,
      consentFlow,
      dpopBoundAccessTokens,
      dpopSigningAlgs,
      dpopMinRsaKeySize,
      authMethod,
      mtlsAuth,
      certificateBoundAccessTokens || edgeFronted,
      jwks,
      generateJwks,
      requireSignedRequestObject,
      requirePushedAuthorizationRequests || edgeFronted,
      edgeSigningKey,
      edgeClientCertificate,
      template = Some(ClientTemplate(ClientKind.web)),
      applicationType = Some(ApplicationType.web),
      issueEdgeClientCertificate,
      enrollEdgeClientCertificate,
    )

/** An app installed on a device, which holds a DPoP key of its own and no credential. Either
  * public (`none`) or fronted by edge (`tls_client_auth`, edge authenticating as the client).
  *
  * Fronted by edge, its access tokens are always DPoP-bound -- the device's key is its only sender
  * constraint -- so `dpopBoundAccessTokens` is not a choice there; a public app may opt in. Its
  * tokens are never certificate-bound and it cannot sign a request object, so those are not
  * fields here. */
@jsonNoExtraFields
case class CreateNativeClientRequest(
    tenantId: TenantId,
    id: ClientId,
    clientName: Map[String, String],
    redirectUris: Set[RedirectUri],
    allowedScopes: Set[ScopeToken],
    permissions: Set[Permission] = Set.empty,
    accessTokenTtl: Int,
    refreshTokenTtl: Option[Int],
    theme: String = "default",
    authFlow: Option[AuthFlow],
    registrationFlow: Option[RegistrationFlow] = None,
    otpTemplateId: String = "default",
    frontChannelLogoutUri: Option[String] = None,
    frontChannelLogoutSessionRequired: Boolean = false,
    backChannelLogoutUri: Option[String] = None,
    logoUri: Option[String] = None,
    policyUri: Option[String] = None,
    tosUri: Option[String] = None,
    consentFlow: Option[ConsentFlowDto] = None,
    dpopBoundAccessTokens: Option[Boolean] = None,
    dpopSigningAlgs: Set[Dpop.Algorithm] = Set.empty,
    dpopMinRsaKeySize: Option[Int] = None,
    authMethod: AuthMethod,
    mtlsAuth: Option[MutualTlsAuth] = None,
    edgeClientCertificate: Option[PrivateClientCertificate] = None,
    issueEdgeClientCertificate: Boolean,
    enrollEdgeClientCertificate: Boolean,
) derives Schema, JsonCodec:
  def toCreateClientRequest: CreateClientRequest =
    CreateClientRequest(
      tenantId,
      id,
      clientName,
      redirectUris,
      allowedScopes,
      permissions,
      accessTokenTtl,
      refreshTokenTtl,
      theme,
      authFlow,
      registrationFlow,
      otpTemplateId,
      frontChannelLogoutUri,
      frontChannelLogoutSessionRequired,
      backChannelLogoutUri,
      logoUri,
      policyUri,
      tosUri,
      consentFlow,
      // The device's key is its only sender constraint once edge authenticates for it, and edge's
      // pushed request is what binds the code to it; edge's certificate is shared by every
      // install, so it binds nothing.
      dpopBoundAccessTokens = dpopBoundAccessTokens.getOrElse(authMethod == AuthMethod.tls_client_auth),
      dpopSigningAlgs,
      dpopMinRsaKeySize,
      authMethod,
      mtlsAuth,
      certificateBoundAccessTokens = false,
      jwks = None,
      generateJwks = None,
      requireSignedRequestObject = false,
      requirePushedAuthorizationRequests = authMethod == AuthMethod.tls_client_auth,
      edgeSigningKey = None,
      edgeClientCertificate,
      template = Some(ClientTemplate(ClientKind.device)),
      applicationType = Some(ApplicationType.native),
      issueEdgeClientCertificate,
      enrollEdgeClientCertificate,
    )

/** Code calling an API on its own behalf, with no user present: no redirect URIs, sign-in flow,
  * consent, logout or branding, no refresh token, and no authorization request to push or sign. */
@jsonNoExtraFields
case class CreateServiceClientRequest(
    tenantId: TenantId,
    id: ClientId,
    clientName: Map[String, String],
    allowedScopes: Set[ScopeToken],
    permissions: Set[Permission] = Set.empty,
    accessTokenTtl: Int,
    dpopBoundAccessTokens: Boolean = false,
    dpopSigningAlgs: Set[Dpop.Algorithm] = Set.empty,
    dpopMinRsaKeySize: Option[Int] = None,
    authMethod: AuthMethod,
    mtlsAuth: Option[MutualTlsAuth] = None,
    certificateBoundAccessTokens: Boolean = false,
    jwks: Option[JsonWebKeySet] = None,
    generateJwks: Option[ClientAssertion.Algorithm] = None,
) derives Schema, JsonCodec:
  def toCreateClientRequest: CreateClientRequest =
    CreateClientRequest(
      tenantId,
      id,
      clientName,
      redirectUris = Set.empty,
      allowedScopes,
      permissions,
      accessTokenTtl,
      refreshTokenTtl = None,
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
      dpopBoundAccessTokens,
      dpopSigningAlgs,
      dpopMinRsaKeySize,
      authMethod,
      mtlsAuth,
      certificateBoundAccessTokens,
      jwks,
      generateJwks,
      requireSignedRequestObject = false,
      requirePushedAuthorizationRequests = false,
      edgeSigningKey = None,
      edgeClientCertificate = None,
      template = Some(ClientTemplate(ClientKind.service)),
      applicationType = Some(ApplicationType.web),
      issueEdgeClientCertificate = false,
      enrollEdgeClientCertificate = false,
    )

// An update names the client and what changes. The client's kind, its `authMethod` and what a
// kind fixes are not fields: they cannot be changed, so a request cannot ask to.

@jsonNoExtraFields
case class UpdateWebClientRequest(
    clientId: ClientId,
    clientName: Option[Map[String, String]],
    redirectUris: PatchClientRedirectUris = PatchClientRedirectUris(Set.empty, Set.empty),
    scope: PatchClientScope = PatchClientScope(Set.empty, Set.empty),
    permissions: PatchPermissions = PatchPermissions(Set.empty, Set.empty),
    accessTokenTtl: Option[Long],
    refreshTokenTtl: Option[Long],
    theme: Option[String],
    authFlow: Option[Patch[AuthFlow]],
    registrationFlow: Option[Patch[RegistrationFlow]],
    otpTemplateId: Option[String],
    frontChannelLogoutUri: Option[Patch[String]],
    frontChannelLogoutSessionRequired: Option[Boolean],
    backChannelLogoutUri: Option[Patch[String]],
    logoUri: Option[Patch[String]],
    policyUri: Option[Patch[String]],
    tosUri: Option[Patch[String]],
    consentFlow: Option[Patch[ConsentFlowDto]],
    dpopBoundAccessTokens: Option[Boolean],
    dpopSigningAlgs: Option[Set[Dpop.Algorithm]],
    dpopMinRsaKeySize: Option[Patch[Int]],
    mtlsAuth: Option[Patch[MutualTlsAuth]],
    certificateBoundAccessTokens: Option[Boolean],
    jwks: Option[Patch[JsonWebKeySet]],
    requireSignedRequestObject: Option[Boolean],
    requirePushedAuthorizationRequests: Option[Boolean],
    edgeSigningKey: Option[Patch[PrivateJsonWebKey]],
    edgeClientCertificate: Option[Patch[PrivateClientCertificate]],
) derives Schema, JsonCodec:
  def toUpdateClientRequest: UpdateClientRequest =
    UpdateClientRequest(
      clientId,
      clientName,
      redirectUris,
      scope,
      permissions,
      accessTokenTtl,
      refreshTokenTtl,
      theme,
      authFlow,
      registrationFlow,
      otpTemplateId,
      frontChannelLogoutUri,
      frontChannelLogoutSessionRequired,
      backChannelLogoutUri,
      logoUri,
      policyUri,
      tosUri,
      consentFlow,
      dpopBoundAccessTokens,
      dpopSigningAlgs,
      dpopMinRsaKeySize,
      authMethod = None,
      mtlsAuth,
      certificateBoundAccessTokens,
      jwks,
      requireSignedRequestObject,
      requirePushedAuthorizationRequests,
      edgeSigningKey,
      edgeClientCertificate,
      applicationType = None,
    )

object UpdateWebClientRequest:
  /** An update that changes nothing, for a caller to set what it wants changed. */
  def of(clientId: ClientId): UpdateWebClientRequest =
    UpdateWebClientRequest(
      clientId,
      clientName = None,
      accessTokenTtl = None,
      refreshTokenTtl = None,
      theme = None,
      authFlow = None,
      registrationFlow = None,
      otpTemplateId = None,
      frontChannelLogoutUri = None,
      frontChannelLogoutSessionRequired = None,
      backChannelLogoutUri = None,
      logoUri = None,
      policyUri = None,
      tosUri = None,
      consentFlow = None,
      dpopBoundAccessTokens = None,
      dpopSigningAlgs = None,
      dpopMinRsaKeySize = None,
      mtlsAuth = None,
      certificateBoundAccessTokens = None,
      jwks = None,
      requireSignedRequestObject = None,
      requirePushedAuthorizationRequests = None,
      edgeSigningKey = None,
      edgeClientCertificate = None,
    )

@jsonNoExtraFields
case class UpdateNativeClientRequest(
    clientId: ClientId,
    clientName: Option[Map[String, String]],
    redirectUris: PatchClientRedirectUris = PatchClientRedirectUris(Set.empty, Set.empty),
    scope: PatchClientScope = PatchClientScope(Set.empty, Set.empty),
    permissions: PatchPermissions = PatchPermissions(Set.empty, Set.empty),
    accessTokenTtl: Option[Long],
    refreshTokenTtl: Option[Long],
    theme: Option[String],
    authFlow: Option[Patch[AuthFlow]],
    registrationFlow: Option[Patch[RegistrationFlow]],
    otpTemplateId: Option[String],
    frontChannelLogoutUri: Option[Patch[String]],
    frontChannelLogoutSessionRequired: Option[Boolean],
    backChannelLogoutUri: Option[Patch[String]],
    logoUri: Option[Patch[String]],
    policyUri: Option[Patch[String]],
    tosUri: Option[Patch[String]],
    consentFlow: Option[Patch[ConsentFlowDto]],
    dpopBoundAccessTokens: Option[Boolean],
    dpopSigningAlgs: Option[Set[Dpop.Algorithm]],
    dpopMinRsaKeySize: Option[Patch[Int]],
    edgeClientCertificate: Option[Patch[PrivateClientCertificate]],
) derives Schema, JsonCodec:
  def toUpdateClientRequest: UpdateClientRequest =
    UpdateClientRequest(
      clientId,
      clientName,
      redirectUris,
      scope,
      permissions,
      accessTokenTtl,
      refreshTokenTtl,
      theme,
      authFlow,
      registrationFlow,
      otpTemplateId,
      frontChannelLogoutUri,
      frontChannelLogoutSessionRequired,
      backChannelLogoutUri,
      logoUri,
      policyUri,
      tosUri,
      consentFlow,
      dpopBoundAccessTokens,
      dpopSigningAlgs,
      dpopMinRsaKeySize,
      authMethod = None,
      mtlsAuth = None,
      certificateBoundAccessTokens = None,
      jwks = None,
      requireSignedRequestObject = None,
      requirePushedAuthorizationRequests = None,
      edgeSigningKey = None,
      edgeClientCertificate,
      applicationType = None,
    )

object UpdateNativeClientRequest:
  /** An update that changes nothing, for a caller to set what it wants changed. */
  def of(clientId: ClientId): UpdateNativeClientRequest =
    UpdateNativeClientRequest(
      clientId,
      clientName = None,
      accessTokenTtl = None,
      refreshTokenTtl = None,
      theme = None,
      authFlow = None,
      registrationFlow = None,
      otpTemplateId = None,
      frontChannelLogoutUri = None,
      frontChannelLogoutSessionRequired = None,
      backChannelLogoutUri = None,
      logoUri = None,
      policyUri = None,
      tosUri = None,
      consentFlow = None,
      dpopBoundAccessTokens = None,
      dpopSigningAlgs = None,
      dpopMinRsaKeySize = None,
      edgeClientCertificate = None,
    )

@jsonNoExtraFields
case class UpdateServiceClientRequest(
    clientId: ClientId,
    clientName: Option[Map[String, String]],
    scope: PatchClientScope = PatchClientScope(Set.empty, Set.empty),
    permissions: PatchPermissions = PatchPermissions(Set.empty, Set.empty),
    accessTokenTtl: Option[Long],
    dpopBoundAccessTokens: Option[Boolean],
    dpopSigningAlgs: Option[Set[Dpop.Algorithm]],
    dpopMinRsaKeySize: Option[Patch[Int]],
    mtlsAuth: Option[Patch[MutualTlsAuth]],
    certificateBoundAccessTokens: Option[Boolean],
    jwks: Option[Patch[JsonWebKeySet]],
) derives Schema, JsonCodec:
  def toUpdateClientRequest: UpdateClientRequest =
    UpdateClientRequest(
      clientId,
      clientName,
      redirectUris = PatchClientRedirectUris(Set.empty, Set.empty),
      scope,
      permissions,
      accessTokenTtl,
      refreshTokenTtl = None,
      theme = None,
      authFlow = None,
      registrationFlow = None,
      otpTemplateId = None,
      frontChannelLogoutUri = None,
      frontChannelLogoutSessionRequired = None,
      backChannelLogoutUri = None,
      logoUri = None,
      policyUri = None,
      tosUri = None,
      consentFlow = None,
      dpopBoundAccessTokens,
      dpopSigningAlgs,
      dpopMinRsaKeySize,
      authMethod = None,
      mtlsAuth,
      certificateBoundAccessTokens,
      jwks,
      requireSignedRequestObject = None,
      requirePushedAuthorizationRequests = None,
      edgeSigningKey = None,
      edgeClientCertificate = None,
      applicationType = None,
    )

object UpdateServiceClientRequest:
  /** An update that changes nothing, for a caller to set what it wants changed. */
  def of(clientId: ClientId): UpdateServiceClientRequest =
    UpdateServiceClientRequest(
      clientId,
      clientName = None,
      accessTokenTtl = None,
      dpopBoundAccessTokens = None,
      dpopSigningAlgs = None,
      dpopMinRsaKeySize = None,
      mtlsAuth = None,
      certificateBoundAccessTokens = None,
      jwks = None,
    )
