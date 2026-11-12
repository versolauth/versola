package versola.central.configuration.partners

import versola.central.configuration.clients.{AuthMethod, ClientId}
import versola.central.configuration.edges.EdgeGroupId
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}
import zio.schema.*

import java.time.Instant

type PartnerId = PartnerId.Type

object PartnerId:
  opaque type Type <: String = String

  inline def apply(string: String): PartnerId = string

  private val validPattern = "^[a-z][a-z0-9-]*$".r
  private val invalidMessage = "Partner ID must start with a lowercase letter and contain only lowercase letters, numbers and hyphens"

  def from(string: String): Either[String, PartnerId] =
    if validPattern.matches(string) then Right(string) else Left(invalidMessage)

  given Schema[PartnerId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonDecoder[PartnerId] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[PartnerId] = JsonEncoder.string.contramap(identity[String])

/** Where a partner stands with the operator (#549 decision 8). `suspended` is the kill switch and acts on every call of the
  * partner's edges, certificate renewal included; `onboarding` is a partner whose first edge has not yet called central, and
  * becomes `active` on that first call. */
enum PartnerStatus(val code: String):
  case Onboarding extends PartnerStatus("onboarding")
  case Active extends PartnerStatus("active")
  case Suspended extends PartnerStatus("suspended")

object PartnerStatus:
  def from(code: String): Either[String, PartnerStatus] =
    values.find(_.code == code).toRight(s"unknown partner status '$code', expected one of ${values.map(_.code).mkString(", ")}")

  given Schema[PartnerStatus] = Schema.primitive[String].transformOrFail(from, status => Right(status.code))
  given JsonDecoder[PartnerStatus] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[PartnerStatus] = JsonEncoder.string.contramap(_.code)

/** Who to reach at the partner. */
case class PartnerContact(
    email: String,
    phone: Option[String],
) derives Schema, JsonCodec

/** What the operator lets a partner have; the client invariant (`PartnerClientRules`) is written against these.
  *
  * @param scopes            what a partner client may request, as a whole: a client's scope is a subset
  * @param redirectDomains   every redirect URI of a partner client is `https` and under one of these (the domain or a subdomain)
  * @param resourceDomains   every resource of the partner is `https` and under one of these; absent means the same domains as
  *                          `redirectDomains` (a partner's API is usually where its applications are)
  * @param maxClients        how many clients the partner may register, pending ones included
  * @param maxEdges          how many edges the partner may enrol in its group
  * @param accessTokenTtlMax the longest access token a partner client may issue, in seconds; it is also how long a token of a
  *                          suspended partner keeps working at `/userinfo`
  * @param refreshTokenTtlMax the longest refresh token, in seconds
  * @param clientAuthMethods how a partner client may authenticate: `tls_client_auth` with the group's certificate, or
  *                          `private_key_jwt`; nothing else
  * @param rateLimitPolicy   the name of the edge rate-limit policy set the operator applies to the partner's group; stored for
  *                          the consoles, central reads nothing from it yet
  */
case class PartnerLimits(
    scopes: Set[ScopeToken],
    redirectDomains: Set[String],
    resourceDomains: Option[Set[String]],
    maxClients: Int,
    maxEdges: Int,
    accessTokenTtlMax: Long,
    refreshTokenTtlMax: Long,
    clientAuthMethods: Set[AuthMethod],
    rateLimitPolicy: String,
) derives Schema, JsonCodec

object PartnerLimits:
  /** The domains a partner's resources may be under: its own `resourceDomains`, else its redirect domains. */
  extension (limits: PartnerLimits) def effectiveResourceDomains: Set[String] = limits.resourceDomains.getOrElse(limits.redirectDomains)

  /** The two ways a partner client may authenticate (#549): a certificate of the partner's group, or a key the partner holds.
    * Never a shared secret, and never nothing. */
  val AllowedAuthMethods: Set[AuthMethod] = Set(AuthMethod.tls_client_auth, AuthMethod.private_key_jwt)

/** What a partner keeps for its own edge and new clients (`PUT /edge/partner/settings`): the defaults its console offers,
  * none of which central applies by itself. */
case class PartnerDefaults(
    backChannelLogoutUri: Option[String],
    accessTokenTtl: Option[Long],
    refreshTokenTtl: Option[Long],
) derives Schema, JsonCodec

case class PartnerSettings(
    defaults: PartnerDefaults,
) derives Schema, JsonCodec

object PartnerSettings:
  val empty: PartnerSettings = PartnerSettings(PartnerDefaults(backChannelLogoutUri = None, accessTokenTtl = None, refreshTokenTtl = None))

/** A partner as stored (#549): a client owner inside `tenantId`, with its own edge group (`groupId`, of kind `partner`, one to
  * one) and its console client (`consoleClientId`, a client of the tenant with `partner_id` set to this partner). */
case class PartnerRecord(
    id: PartnerId,
    tenantId: TenantId,
    displayName: String,
    logoUri: Option[String],
    contact: PartnerContact,
    status: PartnerStatus,
    limits: PartnerLimits,
    groupId: EdgeGroupId,
    consoleClientId: ClientId,
    settings: PartnerSettings,
    /** A reminder for the operator to look at the partner again; central does nothing at that date. */
    reviewAt: Option[Instant],
    createdAt: Instant,
    /** Bumped by every write of the row; a write of the profile or the settings names the version it read and loses when the row
      * has moved on (#962 review: the operator, the partner and an approved profile change write the same row). */
    version: Long,
) derives Schema, CanEqual

object PartnerRecord:
  /** The id of the console client of partner `id`, and of its preset: the console signs in at `/login/<id>-console`. */
  def consoleClientId(id: PartnerId): ClientId = ClientId(s"$id-console")

/** A partner with this id exists. */
case class PartnerAlreadyExists(id: PartnerId) extends RuntimeException(s"partner '$id' already exists")
