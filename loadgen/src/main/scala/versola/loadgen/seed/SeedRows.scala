package versola.loadgen.seed

import versola.loadgen.model.{CredentialKind, Platform, SessionKind, VirtualUser}
import versola.loadgen.store.StoreCodes

import java.time.Instant
import java.util.UUID

/** One seeded user, complete: its `vu_users` row plus whatever credentials its cohort needs in
  * the SUT. Assembled before anything is written, because the write order spans three databases
  * and a row half-derived is worse than one not written.
  */
case class SeededUser(
    user: VirtualUser,
    password: Option[HashedPassword],
    passkey: Option[PasskeyMaterial],
    refreshToken: Option[RefreshTokenMaterial],
):
  /** Set by [[PopulationPlan.userOf]] for every seeded user; unwrapped here rather than at four
    * call sites. A `VirtualUser` with no `sutUserId` has not been seeded, which is a defect in
    * this package, not a case the SQL should encode as NULL.
    */
  def sutUserId: UUID =
    user.sutUserId.getOrElse(
      throw IllegalStateException(s"seeded virtual user ${user.id} has no SUT user id"),
    )

/** Renders [[SeededUser]]s as `COPY` rows, one function per table of [[SutSchema]], with fields
  * in exactly the column order declared there.
  *
  * Field order is the only thing here that can be wrong without failing loudly -- two `uuid`
  * columns or two `bytea` columns swapped is a successful `COPY` and an unusable population -- so
  * each function is written against its [[SutSchema]] table immediately above it, and
  * `SeedRowsSpec` checks the field count against the declared column count.
  */
object SeedRows:

  /** `role_id` comes from [[versola.loadgen.provision.CampaignBlueprint]]'s constants rather than
    * from HOCON, because the campaign's two roles are the definition of the campaign and the
    * provisioner writes them into central from the same constants. A seeder with its own copy in
    * config could grant a role that does not exist, which central would accept here (there is no
    * foreign key to central from auth) and auth would resolve to no permissions -- a population
    * that logs in and then 403s on everything.
    */
  def userRole(user: VirtualUser): String =
    import versola.loadgen.model.UserRole
    user.role match
      case UserRole.RetailUser => versola.loadgen.provision.CampaignBlueprint.retailUserRoleId
      case UserRole.RetailBasic => versola.loadgen.provision.CampaignBlueprint.retailBasicRoleId

  /** `users (id, phone, claims)` */
  def users(seeded: SeededUser): String =
    CopyRow.empty
      .uuid(seeded.sutUserId)
      .text(seeded.user.phone)
      .text("{}")
      .render

  /** `user_passwords (user_id, password, salt, created_at, expires_at)` */
  def userPasswords(seeded: SeededUser, password: HashedPassword, now: Instant): String =
    CopyRow.empty
      .uuid(seeded.sutUserId)
      .bytes(password.hash)
      .bytes(password.salt)
      .instant(now)
      // NULL, not a far-future timestamp: a non-null expiry makes this a *temporary* password,
      // which `PasswordService.verifyPassword` partitions out and answers `Temporary` for.
      .nullValue()
      .render

  /** `user_roles (user_id, tenant_id, role_id)` */
  def userRoles(seeded: SeededUser, tenantId: String): String =
    CopyRow.empty
      .uuid(seeded.sutUserId)
      .text(tenantId)
      .text(userRole(seeded.user))
      .render

  /** `passkeys (id, user_id, public_key, signature_counter, device_type, backed_up,
    * backup_eligible, transports, created_at, updated_at)`
    *
    * `device_type` and `transports` are stored as the *Scala* enum case names, because
    * `PostgresPasskeyRepository` reads them back with `CredentialDeviceType.valueOf` /
    * `AuthenticatorTransport.valueOf` -- so `SingleDevice` and `Internal`, not WebAuthn's
    * `single-device` and `internal`. Getting this wrong throws on the read, not the write.
    *
    * `SingleDevice` and `backed_up = false` because a software authenticator that exists only
    * inside this process is not synced to anything; `Internal` matches the transport
    * `SoftAuthenticator.create` reports.
    */
  def passkeys(seeded: SeededUser, passkey: PasskeyMaterial, now: Instant): String =
    CopyRow.empty
      .bytes(passkey.credentialId)
      .uuid(seeded.sutUserId)
      .bytes(passkey.publicKeyCose)
      .long(0L)
      .text("SingleDevice")
      .boolean(false)
      .boolean(false)
      .textArray(List("Internal"))
      .instant(now)
      .instant(now)
      .render

  /** `refresh_tokens (id, family_id, rotated_at, idempotency_key, session_id, public_session_id,
    * user_id, client_id, audience, scope, issued_at, expires_at, requested_claims, ui_locales,
    * nonce, acr, amr, auth_time, cnf, authorization_details)` -- a warm mobile session's
    * server-side half (§10 step 6).
    *
    * `rotated_at`/`idempotency_key` NULL: a chain that has never rotated has no predecessor and
    * no in-flight retry to recover onto. `requested_claims`/`ui_locales`/`nonce`/`acr`/`cnf`/
    * `authorization_details` NULL: features a real login of these clients never exercises (no
    * `acr_values` on a first login, no DPoP/mTLS binding, no RFC 9396 request) -- see
    * [[RefreshTokenMaterial]].
    *
    * `audience` and `scope` are handed in rather than derived here, because both are the
    * *campaign's* values (every client audience-listed on every resource, `offline_access`
    * always requested), and this module has no business re-deriving what
    * [[versola.loadgen.provision.CampaignBlueprint]] already computed once.
    */
  def refreshTokens(
      seeded: SeededUser,
      material: RefreshTokenMaterial,
      clientId: String,
      audience: List[String],
      scope: List[String],
      amr: List[String],
      now: Instant,
      expiresAt: Instant,
  ): String =
    CopyRow.empty
      .bytes(material.tokenMac)
      .text(base64Url(material.familyId))
      .nullValue()
      .nullValue()
      .bytes(material.sessionMac)
      .text(base64Url(material.publicSessionId))
      .uuid(seeded.sutUserId)
      .text(clientId)
      .textArray(audience)
      .textArray(scope)
      .instant(now)
      .instant(expiresAt)
      .nullValue()
      .nullValue()
      .nullValue()
      .nullValue()
      .text(amr.map(value => "\"" + value + "\"").mkString("[", ",", "]"))
      .instant(now)
      .nullValue()
      .nullValue()
      .render

  /** `vu_sessions (id, user_id, kind, client_id, refresh_token, edge_cookie, sso_session,
    * access_expires_at, refresh_expires_at, acr, auth_time, generation, refresh_generation,
    * shard)` -- the emulator's own half of the same warm session, matching
    * `SessionRunner.mobileRow`'s shape for a freshly logged-in one field for field, except:
    *
    *   - `accessExpiresAt = now`, not `now.plus(accessTokenTtl)`: a session seeded before the
    *     campaign starts must present as already due for a refresh the first time a driver
    *     touches it, not as one that can still coast on its access token. That is the entire
    *     point of warming it (see [[Seeder]]'s doc on §10 step 6).
    *   - `edgeCookie`/`ssoSession` are both NULL: this session never ran a conversation, so it
    *     has neither, matching [[refreshTokens]]'s equally-NULL `acr`/`cnf` -- a resumed session
    *     without an `ssoSession` cannot silently reauthorize or step up and falls back to a
    *     fresh login the first time one of those is needed, same as a real session whose SSO
    *     cookie expired first.
    *   - `generation = refreshGeneration = 0`: a chain that has never rotated, exactly as
    *     `mobileRow` starts one.
    */
  def vuSessions(
      seeded: SeededUser,
      material: RefreshTokenMaterial,
      clientId: String,
      now: Instant,
      refreshExpiresAt: Instant,
  ): String =
    val user = seeded.user
    CopyRow.empty
      .long(user.id)
      .long(user.id)
      .short(StoreCodes.sessionKind.encode(SessionKind.MobileToken))
      .text(clientId)
      .text(base64Url(material.rawToken))
      .nullValue()
      .nullValue()
      .instant(now)
      .instant(refreshExpiresAt)
      .nullValue()
      .instant(now)
      .long(0L)
      .long(0L)
      .short(user.shard.toShort)
      .render

  /** `vu_sessions_columns` the row above fills, mirroring [[vuUsersCopyStatement]]'s reasoning:
    * the emulator's own schema, shared ownership with the store, not part of the §3.4 coupling.
    */
  val vuSessionsCopyStatement: String =
    "COPY vu_sessions (id, user_id, kind, client_id, refresh_token, edge_cookie, sso_session, " +
      "access_expires_at, refresh_expires_at, acr, auth_time, generation, refresh_generation, shard) " +
      "FROM STDIN WITH (FORMAT csv, NULL '')"

  /** Only the mobile cohort: web's `full-login-probability` of 0.85 deliberately favours a fresh
    * login over a resumed cookie session (§2.3), so warming a web session would be warming
    * traffic the design already sends through the front door. Mobile's 0.033 is the inverse bet
    * -- almost every arrival should find a session -- and a freshly-seeded population that has
    * never arrived once has nothing to resume, which is the ratio problem §10 step 6 exists to
    * fix (see [[Seeder]]).
    */
  def needsWarmSession(user: VirtualUser): Boolean = user.platform == Platform.Mobile

  /** The mobile client id a warm session's credential kind logs in with, matching
    * `SessionRunner.mobileLogin`'s own dispatch (`clients.mobileClientFor`) so a resumed session
    * presents to the same client its first login would have.
    */
  def mobileClientId(credential: CredentialKind): String =
    import versola.loadgen.provision.CampaignBlueprint.*
    credential match
      case CredentialKind.Otp => mobileOtpClientId
      case CredentialKind.OtpPassword => mobileOtpPasswordClientId
      case CredentialKind.Passkey => mobilePasskeyClientId

  /** RFC 8176 `amr` for the credential kind a warm session's login would have produced --
    * [[versola.oauth.client.model.AuthMethodRef]]'s own doc: "OTP → `{otp, sms}`, passkey →
    * `{swk, user}`". `pwd` alone for the password cohort: `mobile-otp-password`'s primary step is
    * the password, not the OTP that gates enrolling it (§B).
    */
  def amrFor(credential: CredentialKind): List[String] = credential match
    case CredentialKind.Otp => List("otp", "sms")
    case CredentialKind.OtpPassword => List("pwd")
    case CredentialKind.Passkey => List("swk", "user")

  private def base64Url(bytes: Array[Byte]): String =
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** `user_index (id, phone)` */
  def userIndex(seeded: SeededUser): String =
    CopyRow.empty
      .uuid(seeded.sutUserId)
      .text(seeded.user.phone)
      .render

  /** `vu_users`, in the column order V0001 declares and `PostgresVirtualUserRepository` reads.
    * The enum columns go through [[StoreCodes]] rather than `ordinal`, for the reason that object
    * exists: a case inserted into the middle of an enum must not reinterpret rows already on
    * disk.
    */
  def vuUsers(seeded: SeededUser): String =
    val user = seeded.user
    CopyRow.empty
      .long(user.id)
      .optionalUuid(user.sutUserId)
      .text(user.phone)
      .optionalText(user.password)
      .short(StoreCodes.activityClass.encode(user.activityClass))
      .short(StoreCodes.platform.encode(user.platform))
      .short(StoreCodes.credential.encode(user.credential))
      .short(StoreCodes.role.encode(user.role))
      .optionalBytes(seeded.passkey.map(_.privateKey))
      .optionalText(seeded.passkey.map(passkey => credentialIdOf(passkey)))
      .short(StoreCodes.userState.encode(user.state))
      .short(user.shard.toShort)
      .optionalInstant(user.lastSeenAt)
      .render

  /** The `vu_users_columns` the row above fills, as the `COPY` statement names them. Kept next to
    * the renderer rather than in [[SutSchema]]: this is the emulator's own schema, which the
    * seeder shares ownership of with the store, so it is not part of the §3.4 coupling the guard
    * watches.
    */
  val vuUsersCopyStatement: String =
    "COPY vu_users (id, sut_user_id, phone, password, activity_class, platform, credential, role, " +
      "passkey_key, passkey_cred_id, state, shard, last_seen_at) FROM STDIN WITH (FORMAT csv, NULL '')"

  /** Base64url, matching `SoftAuthenticator`'s `Credential.id` -- the driver compares the
    * credential id auth echoes in the assertion options against this string.
    */
  def credentialIdOf(passkey: PasskeyMaterial): String =
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(passkey.credentialId)

  /** Which cohorts need which credential, in one place so the writer and the planner cannot
    * disagree about whether a user has a password.
    */
  def needsPassword(user: VirtualUser): Boolean = user.credential == CredentialKind.OtpPassword

  def needsPasskey(user: VirtualUser): Boolean = user.credential == CredentialKind.Passkey
