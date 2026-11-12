package versola.audit

import versola.util.audit.AuditDetails
import zio.json.JsonEncoder

/** The payload of each kind of audit event auth records (`details` of the row), one case class per kind. Every field is a
  * name, a count, a flag or a short prefix: nothing here is a secret, a token, a password or a one-time code, and a `Secret`
  * cannot be put in since it has no encoder. A refresh token appears only as the prefix `Observability` keeps.
  *
  * `Option` fields are left out of the JSON when empty. */
object AuthAuditDetails:

  /** `login.step`: one step of a conversation judged. `step` is the conversation's step (`password`, `otp`, `passkey`, ...), `kind`
    * as `AuthMetrics.conversationKind` (`login`, `step_up`, `reauth`); `subjectHash` is the keyed hash antifraud keeps when no user
    * is resolved yet, `afDecision` what antifraud decided when it was consulted. */
  case class LoginStep(
      step: String,
      conversationId: String,
      kind: String,
      subjectHash: Option[String],
      afDecision: Option[String],
  ) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** `login.completed`: the session was created. `priorSession` says whether it replaced one (a step-up or a re-authentication): the
    * conversation holds only the keyed MAC of the prior session's id, which is no id to publish, and the public `sid` of the session
    * created is the event's own `sessionId`. */
  case class LoginCompleted(
      kind: String,
      conversationId: String,
      acr: Option[String],
      amr: List[String],
      priorSession: Boolean,
  ) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** The details of an event that is counted rather than written for each occurrence (see `ClientAuthFailures`): `counted` is the
    * same details with how many occurrences the row stands for. Everything else in them is a closed word, so the key of the count stays
    * bounded. */
  trait Coalesced extends AuditDetails:
    def counted(count: Int): AuditDetails

  case class AuthorizeRequest(
      responseType: List[String],
      scope: List[String],
      resource: List[String],
      prompt: List[String],
      acrValues: List[String],
      par: Boolean,
      count: Option[Int],
  ) extends Coalesced derives JsonEncoder:
    def toJson = AuditDetails.render(this)
    override def counted(count: Int): AuditDetails = copy(count = Some(count))

  case class ParRequest(scope: List[String], requestObject: Boolean, count: Option[Int]) extends Coalesced derives JsonEncoder:
    def toJson = AuditDetails.render(this)
    override def counted(count: Int): AuditDetails = copy(count = Some(count))

  /** `consent.granted`, `consent.denied` and `consent.revoked`. */
  case class Consent(
      scope: List[String],
      authorizationDetailTypes: List[String],
      expiresAt: Option[String],
  ) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** `token.issued`. `binding` is `none`, `dpop` or `mtls`; `clientAuth` is the method the client registered. */
  case class TokenIssued(
      grant: String,
      scope: List[String],
      resource: List[String],
      accessJti: String,
      refreshPrefix: Option[String],
      previousRefreshPrefix: Option[String],
      binding: String,
      idToken: Boolean,
      clientAuth: String,
  ) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** `token.refused`: `reason` of the event is the OAuth error, `detail` the sub-code `setError` carries. */
  case class TokenRefused(grant: Option[String], clientAuth: Option[String], detail: Option[String], count: Option[Int])
    extends Coalesced derives JsonEncoder:
    def toJson = AuditDetails.render(this)
    override def counted(count: Int): AuditDetails = copy(count = Some(count))

  /** `token.reuse_detected`: a refresh token that had been rotated away (or an authorization code that had been used) was
    * presented again, and the family it belongs to was revoked. */
  case class TokenReuseDetected(tokenType: String, refreshPrefix: Option[String], familyId: String) extends AuditDetails
    derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  case class TokenRevoked(tokenType: String, jti: Option[String], refreshPrefix: Option[String], cause: String, count: Option[Int])
    extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** `client.auth_failed`, coalesced per (client, address, reason) and minute: `count` failures in the minute. */
  case class ClientAuthFailed(method: String, count: Int, assertionJti: Option[String]) extends Coalesced derives JsonEncoder:
    def toJson = AuditDetails.render(this)
    override def counted(count: Int): AuditDetails = copy(count = count)

  /** `session.ended`: `cause` is `rp_logout`, `user_revoked`, `admin_revoked`, `user_agent_forgotten` or `user_deleted`;
    * `clients` how many relying parties took part in the session. */
  case class SessionEnded(cause: String, clients: Int) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** The `account.*` kinds that carry nothing but how they came about. */
  case class Account(via: String, detail: Option[String]) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** `account.passkey_enrolled` and `account.passkey_removed`: the credential by an id prefix, never the credential. */
  case class Passkey(credentialPrefix: Option[String], deviceType: Option[String]) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** `antifraud.list_changed`: `action` is `upsert` or `delete`. The value of the entry is not here: it can be a phone's hash or an
    * address, and `af_list_audit` keeps it for the antifraud console. */
  case class AntifraudListChanged(
      action: String,
      listType: String,
      valueType: String,
      tenantId: Option[String],
      entryId: Option[String],
      shadow: Option[Boolean],
      expiresAt: Option[String],
  ) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** `antifraud.settings_changed`: `action` is `put`, `patch` or `reset`, `version` the one the row became. */
  case class AntifraudSettingsChanged(action: String, version: Option[Long]) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** `antifraud.counters_reset`: `generation` is the one the reset took. */
  case class AntifraudCountersReset(scope: String, valueType: Option[String], generation: Long) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** `account.updated`: the NAMES of the identifiers that changed (`email`, `phone`, `login`), never their values. */
  case class AccountUpdated(changed: List[String]) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** `account.roles_updated`: what the request asked for in one tenant. */
  case class RolesUpdated(tenantId: String, added: List[String], removed: List[String]) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)

  /** `account.claims_updated`: the names of the claims, never their values. */
  case class ClaimsUpdated(claims: List[String]) extends AuditDetails derives JsonEncoder:
    def toJson = AuditDetails.render(this)
