package versola.central.configuration.clients

import versola.central.configuration.edges.EdgeCertificate
import versola.central.configuration.partners.{PartnerLimits, PartnerRecord}
import versola.central.configuration.scopes.ScopeToken
import versola.util.RedirectUri
import zio.Duration
import zio.http.{Scheme, URL}

import java.util.Locale

/** What a client a partner owns is held to (#549, #962), on top of every rule a client of its kind is held to anyway.
  *
  * Two kinds of rule. The ones that make the client *a partner's client at all* are refused outright, on every surface: a
  * partner client signs users in with consent and authenticates with the group's certificate or a key the partner holds,
  * and its tokens are never bound to one replica's certificate. The ones about the operator's *limits* are what an
  * operator may change their mind about: a client outside them is refused when the operator registers it, and recorded as
  * waiting for approval when the partner does, with the fields named ([[outsideLimits]]). */
object PartnerClientRules:

  /** A way the registration falls short of the limits: the field, and why. */
  case class Violation(field: String, reason: String)

  /** The registration as it will be stored, in the terms the rules read. */
  case class Subject(
      applicationType: ApplicationType,
      kind: Option[ClientKind],
      consentFlow: Boolean,
      authMethod: AuthMethod,
      mtlsAuth: Option[MutualTlsAuth],
      certificateBoundAccessTokens: Boolean,
      redirectUris: Set[RedirectUri],
      scope: Set[ScopeToken],
      accessTokenTtl: Duration,
      refreshTokenTtl: Duration,
  )

  object Subject:
    def of(client: OAuthClientRecord): Subject =
      Subject(
        applicationType = client.applicationType,
        kind = client.template.map(_.kind),
        consentFlow = client.consentFlow.isDefined,
        authMethod = client.authMethod,
        mtlsAuth = client.mtlsAuth,
        certificateBoundAccessTokens = client.certificateBoundAccessTokens,
        redirectUris = client.redirectUris,
        scope = client.scope,
        accessTokenTtl = client.accessTokenTtl,
        refreshTokenTtl = client.refreshTokenTtl,
      )

  /** The `mtlsAuth` of a partner's `tls_client_auth` client: the group's name, which every edge of the group carries. */
  def groupAuth(partner: PartnerRecord): MutualTlsAuth =
    MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_uri, EdgeCertificate.groupUriFor(partner.groupId))

  /** What makes the client a partner's client, or not one at all. */
  def invariant(clientId: ClientId, partner: PartnerRecord, subject: Subject): Option[InvalidRegistrationConfiguration] =
    def invalid(reason: String) = Some(InvalidRegistrationConfiguration(clientId, s"partner client $reason"))
    if subject.kind.contains(ClientKind.service) then
      invalid("is never a service client - a partner owns applications that sign the operator's users in")
    else if !subject.consentFlow then
      invalid("needs a consentFlow - the operator's users are told which partner they sign in to")
    else if !PartnerLimits.AllowedAuthMethods.contains(subject.authMethod) then
      invalid(s"authenticates with tls_client_auth (the group's certificate) or private_key_jwt, not ${subject.authMethod}")
    else if !partner.limits.clientAuthMethods.contains(subject.authMethod) then
      invalid(
        s"may not authenticate with ${subject.authMethod}: partner '${partner.id}' is limited to ${partner.limits.clientAuthMethods.mkString(", ")}",
      )
    else if subject.authMethod == AuthMethod.tls_client_auth && !subject.mtlsAuth.contains(groupAuth(partner)) then
      invalid(
        s"with tls_client_auth is recognised by its group's certificate: mtlsAuth must be san_uri ${EdgeCertificate.groupUriFor(partner.groupId)}",
      )
    else if subject.certificateBoundAccessTokens then
      invalid("cannot set certificateBoundAccessTokens - every edge of the partner's group presents a certificate of its own")
    else None

  /** Where the client is outside the partner's limits, field by field: an operator may approve it anyway, with overrides. */
  def outsideLimits(partner: PartnerRecord, subject: Subject): List[Violation] =
    val limits = partner.limits
    val uris = subject.redirectUris.toList.sortBy(uri => uri: String).filterNot(underDomains(_, limits.redirectDomains))
    val scope = (subject.scope -- limits.scopes).toList.sorted
    List(
      Option.when(uris.nonEmpty)(
        Violation(
          "redirectUris",
          s"${uris.mkString(", ")}: every redirect URI is https and under one of ${limits.redirectDomains.toList.sorted.mkString(", ")}",
        ),
      ),
      Option.when(scope.nonEmpty)(Violation("scope", s"${scope.mkString(" ")}: not in the partner's scopes")),
      Option.when(subject.accessTokenTtl.toSeconds > limits.accessTokenTtlMax)(
        Violation("accessTokenTtl", s"${subject.accessTokenTtl.toSeconds}s exceeds the partner's maximum of ${limits.accessTokenTtlMax}s"),
      ),
      Option.when(subject.refreshTokenTtl.toSeconds > limits.refreshTokenTtlMax)(
        Violation("refreshTokenTtl", s"${subject.refreshTokenTtl.toSeconds}s exceeds the partner's maximum of ${limits.refreshTokenTtlMax}s"),
      ),
    ).flatten

  /** [[outsideLimits]] as a registration failure, for the surfaces that do not record a request for approval. */
  def validateLimits(clientId: ClientId, partner: PartnerRecord, subject: Subject): Option[InvalidRegistrationConfiguration] =
    outsideLimits(partner, subject) match
      case Nil => None
      case violations =>
        Some(InvalidRegistrationConfiguration(
          clientId,
          violations.map(v => s"${v.field} ${v.reason}").mkString("partner client outside its limits: ", "; ", ""),
        ))

  /** One more client than the partner may have. `existing` counts every client of the partner, pending ones included: a
    * registration that waits for approval still takes a slot. */
  def validateClientCount(clientId: ClientId, partner: PartnerRecord, existing: Int): Option[InvalidRegistrationConfiguration] =
    Option.when(existing >= partner.limits.maxClients):
      InvalidRegistrationConfiguration(clientId, s"partner '${partner.id}' may register at most ${partner.limits.maxClients} clients")

  /** `https`, and the host is one of `domains` or below one of them. A domain is compared case-insensitively, as DNS is. */
  def underDomains(uri: RedirectUri, domains: Set[String]): Boolean =
    URL.decode(uri).toOption.exists: url =>
      url.scheme.contains(Scheme.HTTPS) && url.host.exists: host =>
        val lower = host.toLowerCase(Locale.ROOT)
        domains.map(_.toLowerCase(Locale.ROOT)).exists(domain => lower == domain || lower.endsWith(s".$domain"))
