package versola.loadgen.seed

import versola.loadgen.model.{CredentialKind, VirtualUser}
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
