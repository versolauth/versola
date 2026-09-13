package versola.loadgen.model

import versola.util.Secret

import java.time.Instant
import java.util.UUID

/** Cohort a virtual user belongs to (versola-loadgen-dev-spec.md §5's `population.classes`,
  * design doc §2). Drives how often the user shows up at all, independent of platform/credential.
  */
enum ActivityClass:
  case Heavy, Regular, Light, Dormant

/** Which client family a user's sessions authenticate through -- `mobile-*` clients vs the
  * edge/cookie path (§8.1-8.4).
  */
enum Platform:
  case Mobile, Web

/** Which conversation shape a user's login takes (§8.1-8.3). */
enum CredentialKind:
  case Otp, OtpPassword, Passkey

/** Central role assignment -- `retail-basic` is what exercises the 403 path on step-up-gated
  * actions (§4's `ProtocolError.Forbidden`).
  */
enum UserRole:
  case RetailUser, RetailBasic

/** Where a virtual user is in its lifecycle against the SUT. `Planned` means the population plan
  * exists but nothing has been written to the SUT yet, `Registered` means `sutUserId` is set and
  * the credentials work, and `Broken` is terminal for a user whose login fails in a way no retry
  * will fix -- the scheduler stops picking it, so the population counts stay honest.
  */
enum VirtualUserState:
  case Planned, Registered, Broken

/** One row of `vu_users` (§6, migration V0001). `id` is the dense shard key -- `shard = id %
  * shardCount` (§7.1) -- deliberately not `sutUserId`, which stays `None` until the user is
  * seeded or registers for real.
  *
  * Field order matches the column order of V0001 and of every SELECT in
  * `PostgresVirtualUserRepository`: magnum reads a derived codec positionally.
  *
  * This is the persisted shape, not a projection of it. An earlier version of W1 carried only
  * the fields a scenario reads to decide what a user does, and the store defined a `VirtualUserRow`
  * superset alongside it -- but the omitted fields were exactly the credentials that make a user
  * usable after a driver restart, which the scenario engine needs too, so the two would have
  * converged anyway while drifting in the meantime.
  *
  * @param password
  *   plaintext, test-only (the SUT stores an Argon2id hash of it). Not a [[Secret]] because the
  *   seeder must read it back to log the user in, and because there is nothing here worth
  *   protecting -- every value is generated.
  * @param passkeyKey
  *   PKCS#8 P-256 private key of the software authenticator. [[Secret]] rather than a bare
  *   `Array[Byte]`, so it cannot reach a log line through a `toString` of this type.
  */
case class VirtualUser(
    id: Long,
    sutUserId: Option[UUID],
    phone: String,
    password: Option[String],
    activityClass: ActivityClass,
    platform: Platform,
    credential: CredentialKind,
    role: UserRole,
    passkeyKey: Option[Secret],
    passkeyCredId: Option[String],
    state: VirtualUserState,
    shard: Int,
    lastSeenAt: Option[Instant],
)
