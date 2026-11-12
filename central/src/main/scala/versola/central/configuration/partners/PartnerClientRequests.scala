package versola.central.configuration.partners

import versola.central.configuration.clients.{ApplicationType, AuthFlow, PartnerClientRules}
import versola.central.configuration.clients.{AuthMethod, ClientId, ClientKind, ClientTemplate, OAuthClientRecord}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.{
  ConsentFlowDto,
  CreateClientRequest,
  PatchClientRedirectUris,
  PatchClientScope,
  PatchPermissions,
  UpdateClientRequest,
}
import versola.util.{JsonWebKeySet, Patch, RedirectUri}
import zio.json.{JsonCodec, jsonNoExtraFields}
import zio.schema.*

/** A client as a partner registers it through its edge (`POST /edge/partner/clients`, #962): what the partner chooses. What
  * a partner client has fixed is not a field, so it cannot be sent wrong -- consent is on, PAR is on, the certificate is the
  * group's, tokens are not certificate-bound, a native client's tokens are DPoP-bound. This is also the shape stored as
  * `requested` of a `client_create` approval, and what the operator's `overrides` are read as. */
@jsonNoExtraFields
case class PartnerClientRequest(
    id: ClientId,
    kind: PartnerClientKind,
    clientName: Map[String, String],
    logoUri: Option[String],
    policyUri: Option[String],
    tosUri: Option[String],
    redirectUris: Set[RedirectUri],
    scope: Set[ScopeToken],
    authMethod: AuthMethod,
    /** The partner's own keys, for `private_key_jwt`; nothing for `tls_client_auth`, where the group's certificate is the credential. */
    jwks: Option[JsonWebKeySet],
    accessTokenTtl: Int,
    refreshTokenTtl: Option[Int],
    backChannelLogoutUri: Option[String],
    /** Absent asks for the default consent screen: every requested scope shown, none deselectable. */
    consentFlow: Option[ConsentFlowDto],
) derives Schema, JsonCodec:

  /** The registration central makes of it, for the partner `partner`: a web or native client of the partner's tenant with
    * what a partner client has fixed written in. */
  def toCreateClientRequest(partner: PartnerRecord): CreateClientRequest =
    val native = kind == PartnerClientKind.native
    CreateClientRequest(
      tenantId = partner.tenantId,
      id = id,
      clientName = clientName,
      redirectUris = redirectUris,
      allowedScopes = scope,
      permissions = Set.empty,
      accessTokenTtl = accessTokenTtl,
      refreshTokenTtl = refreshTokenTtl,
      theme = "default",
      // how the operator's users sign in is the operator's to say, per client (`PUT /configuration/clients/web`); until it does, the
      // default of the platform: a client with none is "not configured for sign-in" at auth
      authFlow = Some(AuthFlow.default),
      otpTemplateId = "default",
      frontChannelLogoutUri = None,
      frontChannelLogoutSessionRequired = false,
      backChannelLogoutUri = backChannelLogoutUri,
      logoUri = logoUri,
      policyUri = policyUri,
      tosUri = tosUri,
      consentFlow = Some(consentFlow.getOrElse(PartnerClientRequest.DefaultConsent)),
      // the device's key is the only sender constraint of a partner's native client (#963: native keeps DPoP on)
      dpopBoundAccessTokens = native,
      dpopSigningAlgs = Set.empty,
      dpopMinRsaKeySize = None,
      authMethod = authMethod,
      mtlsAuth = Option.when(authMethod == AuthMethod.tls_client_auth)(PartnerClientRules.groupAuth(partner)),
      certificateBoundAccessTokens = false,
      jwks = jwks,
      generateJwks = None,
      requireSignedRequestObject = false,
      requirePushedAuthorizationRequests = true,
      edgeSigningKey = None,
      edgeClientCertificate = None,
      template = Some(ClientTemplate(if native then ClientKind.device else ClientKind.web)),
      applicationType = Some(if native then ApplicationType.native else ApplicationType.web),
      issueEdgeClientCertificate = false,
      enrollEdgeClientCertificate = false,
      themeOverrides = None,
      estEnrollment = false,
      partnerId = Some(partner.id),
    )

object PartnerClientRequest:
  val DefaultConsent: ConsentFlowDto =
    ConsentFlowDto(allowPartial = false, rememberDuration = None, requiredScopes = Set.empty, offlineAccessOptional = false)

/** What a partner's application is: a site with a backend, or an app on a device. Never a service (#549). */
enum PartnerClientKind derives JsonCodec, Schema:
  case web, native

/** A change a partner asks for (`PUT /edge/partner/clients`): the fields named change, the rest stay. A set replaces the
  * stored one as a whole. The shape of `requested` of a `client_change` approval, and of the operator's `overrides`. */
@jsonNoExtraFields
case class PartnerClientUpdate(
    id: ClientId,
    clientName: Option[Map[String, String]],
    logoUri: Option[Patch[String]],
    policyUri: Option[Patch[String]],
    tosUri: Option[Patch[String]],
    redirectUris: Option[Set[RedirectUri]],
    scope: Option[Set[ScopeToken]],
    jwks: Option[JsonWebKeySet],
    accessTokenTtl: Option[Long],
    refreshTokenTtl: Option[Long],
    backChannelLogoutUri: Option[Patch[String]],
    consentFlow: Option[ConsentFlowDto],
) derives Schema, JsonCodec:

  /** The fields this update sets. */
  def fields: Set[String] =
    List(
      clientName.map(_ => "clientName"),
      logoUri.map(_ => "logoUri"),
      policyUri.map(_ => "policyUri"),
      tosUri.map(_ => "tosUri"),
      redirectUris.map(_ => "redirectUris"),
      scope.map(_ => "scope"),
      jwks.map(_ => "jwks"),
      accessTokenTtl.map(_ => "accessTokenTtl"),
      refreshTokenTtl.map(_ => "refreshTokenTtl"),
      backChannelLogoutUri.map(_ => "backChannelLogoutUri"),
      consentFlow.map(_ => "consentFlow"),
    ).flatten.toSet

  /** Whether the update touches what the consent screen shows (#549 decision 7), which always needs the operator. */
  def touchesConsentScreen: Boolean = clientName.isDefined || logoUri.isDefined

  /** The patch central applies to `current`: a set given here replaces the stored one, so it becomes an add of what is new
    * and a remove of what is gone. */
  def toUpdateClientRequest(current: OAuthClientRecord): UpdateClientRequest =
    UpdateClientRequest(
      clientId = id,
      clientName = clientName,
      redirectUris = redirectUris.fold(PatchClientRedirectUris(Set.empty, Set.empty))(uris =>
        PatchClientRedirectUris(add = uris -- current.redirectUris, remove = current.redirectUris -- uris),
      ),
      scope = scope.fold(PatchClientScope(Set.empty, Set.empty))(tokens =>
        PatchClientScope(add = tokens -- current.scope, remove = current.scope -- tokens),
      ),
      permissions = PatchPermissions(Set.empty, Set.empty),
      accessTokenTtl = accessTokenTtl,
      refreshTokenTtl = refreshTokenTtl,
      theme = None,
      authFlow = None,
      otpTemplateId = None,
      frontChannelLogoutUri = None,
      frontChannelLogoutSessionRequired = None,
      backChannelLogoutUri = backChannelLogoutUri,
      logoUri = logoUri,
      policyUri = policyUri,
      tosUri = tosUri,
      consentFlow = consentFlow.map(Patch.Modified(_)),
      dpopBoundAccessTokens = None,
      dpopSigningAlgs = None,
      dpopMinRsaKeySize = None,
      authMethod = None,
      mtlsAuth = None,
      certificateBoundAccessTokens = None,
      jwks = jwks.map(Patch.Modified(_)),
      requireSignedRequestObject = None,
      requirePushedAuthorizationRequests = None,
      edgeSigningKey = None,
      edgeClientCertificate = None,
      applicationType = None,
      themeOverrides = None,
    )

object PartnerClientUpdate:
  /** An update that changes nothing, for the operator's overrides to be laid over. */
  def of(id: ClientId): PartnerClientUpdate =
    PartnerClientUpdate(
      id = id,
      clientName = None,
      logoUri = None,
      policyUri = None,
      tosUri = None,
      redirectUris = None,
      scope = None,
      jwks = None,
      accessTokenTtl = None,
      refreshTokenTtl = None,
      backChannelLogoutUri = None,
      consentFlow = None,
    )
