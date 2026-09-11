package versola.loadgen.model

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

/** One row of `vu_users` (§6, migration L0001). `id` is the dense shard key -- `shard = id %
  * shardCount` (§7.1) -- deliberately not `sutUserId`, which stays `None` until the user is
  * seeded or registers for real.
  */
case class VirtualUser(
    id: Long,
    sutUserId: Option[UUID],
    phone: String,
    activityClass: ActivityClass,
    platform: Platform,
    credential: CredentialKind,
    role: UserRole,
    shard: Int,
)
