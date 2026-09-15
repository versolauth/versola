package versola.loadgen.seed

import versola.loadgen.config.PopulationConfig
import versola.loadgen.model.*
import versola.loadgen.scheduler.ShardAssignment

import java.nio.ByteBuffer
import java.util.UUID

/** Turns the campaign's population mix (versola-loadgen-dev-spec.md §5's `population`, design
  * doc §2.1-2.2) into `vu_users` rows, as a **total function of the virtual-user id**.
  *
  * No `Random`, no seed, no state -- for the same reason
  * [[versola.loadgen.scheduler.ShardAssignment]] has none. The seeder, the drivers and the
  * coordinator must agree about a user without consulting anything, and "we tested it and it was
  * stable" is a weaker claim than "it cannot vary". Concretely it buys three things: a seed run
  * that died at user 640,000 resumes without re-deriving what it already wrote, a re-seed of the
  * same id range reproduces the same population byte for byte, and a `vu_users` row that was
  * lost (the table is `UNLOGGED`, so a crash truncates it) can be rebuilt from its id.
  *
  * Cohorts are assigned by hashing the id, not by a modulus of it. `id % 100 < 35` would be
  * simpler and exact, but `shard = id % shardCount` (§7.1) is also a modulus of the same id, and
  * the two would be correlated: at `shardCount = 8` a cohort boundary on any multiple of 8
  * lands whole cohorts on a subset of the drivers. A driver would then run a population that is
  * not the campaign's -- an error nothing reports, because every individual row is valid. The
  * price of hashing is that cohort shares are met to within sampling noise rather than exactly
  * (~0.1% at 1M, ~1.5% at 1,000); `PopulationPlanSpec` pins both the shares and their
  * independence from the shard.
  *
  * The one part of a seeded user that is *not* a function of its id is its passkey key pair,
  * which [[PasskeyMaterial]] draws freshly per seed run -- a P-256 scalar cannot be derived from
  * an id without writing the curve arithmetic by hand. So a lost `vu_users` row is rebuildable
  * for the 75% of the population that carries no passkey, and costs a re-enrolment for the rest.
  */
object PopulationPlan:

  /** German mobile numbers, the same range and prefix `e2e`'s `CentralApi.phone` uses -- central
    * and auth both parse these with libphonenumber, so the prefix and the digit count are load
    * bearing and this is the one form already proven against both. Derived from the id rather
    * than randomly, unlike e2e's, because uniqueness has to hold across a resumed seed run and
    * because the drivers must be able to recompute it.
    *
    * Eight digits is 100M distinct numbers, which covers the design doc's 20M ceiling five times
    * over. [[maxSupportedId]] is the point at which it stops being true, and the seeder refuses
    * a range that crosses it rather than silently wrapping two users onto one number.
    */
  val maxSupportedId: Long = 99_999_999L

  def phoneOf(id: Long): String = f"+49151$id%08d"

  /** The SUT's `users.id`, derived from the virtual-user id so that every table the seeder wrote
    * can be found again from the id alone. That is what makes the pre-write delete of §10's
    * resume path exact: the seeder knows which UUIDs the interrupted batch would have used
    * without having to read them back from a `vu_users` row that may not have been written.
    *
    * A name-based UUID over the virtual-user id rather than `UUID.randomUUID()`, and marked
    * version 4 rather than version 5 because it is not a real RFC 4122 name-based UUID (there is
    * no namespace and the hash is SHA-256, not SHA-1) and claiming otherwise would be worse than
    * claiming nothing. Nothing in auth reads structure out of `users.id`: it is a primary key and
    * the `sub` claim, never sorted on and never time-ordered (unlike `user_outbox.id`, which is
    * documented as UUIDv7 precisely because its ordering is load bearing).
    */
  def sutUserIdOf(id: Long): UUID =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update("versola.loadgen.seed.user".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    digest.update(ByteBuffer.allocate(java.lang.Long.BYTES).putLong(id).array())
    val bytes = digest.digest()
    bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte // version 4
    bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte // variant RFC 4122
    val buffer = ByteBuffer.wrap(bytes)
    UUID(buffer.getLong, buffer.getLong)

  /** The plaintext the password cohort logs in with. Long enough and mixed enough to satisfy a
    * default password regex, and derived from the id so that a lost `vu_users` row does not cost
    * a re-hash of the user. Test-only by construction -- see [[VirtualUser.password]].
    */
  def passwordOf(id: Long): String = f"Load!$id%08dqZ"

  /** One `vu_users` row. `state` is [[VirtualUserState.Registered]] and `sutUserId` is set
    * because a seeded user *is* registered: its credentials exist in the SUT and work. `Planned`
    * is for the registration ramp's population (§12), which the seeder does not produce.
    *
    * `passkeyKey`/`passkeyCredId` are left `None` here and filled in by [[PasskeyEnrolment]] for
    * the passkey cohort only: generating a P-256 key pair costs ~0.1 ms and the other 75% of the
    * population has no use for one.
    */
  def userOf(config: PopulationConfig, shardCount: Int, id: Long): VirtualUser =
    val credential = draw(id, CredentialSalt, credentialShares(config))
    VirtualUser(
      id = id,
      sutUserId = Some(sutUserIdOf(id)),
      phone = phoneOf(id),
      password = Option.when(credential == CredentialKind.OtpPassword)(passwordOf(id)),
      activityClass = draw(id, ActivitySalt, activityShares(config)),
      platform = draw(id, PlatformSalt, platformShares(config)),
      credential = credential,
      role = draw(id, RoleSalt, roleShares(config)),
      passkeyKey = None,
      passkeyCredId = None,
      state = VirtualUserState.Registered,
      shard = ShardAssignment.shardOf(id, shardCount),
      lastSeenAt = None,
    )

  /** Rejects the population mixes that produce a campaign whose load is not the one configured,
    * rather than letting the last cohort silently absorb the difference. Called once, before any
    * hashing; the seeder's cost centre is downstream of it.
    */
  def validate(config: PopulationConfig, shardCount: Int, count: Long): Either[String, Unit] =
    def sharesSumToOne(label: String, shares: List[(?, Double)]): Either[String, Unit] =
      val total = shares.map(_._2).sum
      if shares.exists((_, share) => share.isNaN || share < 0.0) then
        Left(s"population.$label has a negative or non-finite share")
      else if math.abs(total - 1.0) > ShareTolerance then
        Left(s"population.$label shares sum to $total, not 1.0")
      else Right(())

    for
      _ <- Either.cond(count > 0, (), s"population.target must be positive, got $count")
      _ <- Either.cond(
        count <= maxSupportedId,
        (),
        s"population.target $count exceeds the $maxSupportedId distinct phone numbers " +
          "PopulationPlan.phoneOf can produce; widen phoneOf before seeding a population this large",
      )
      _ <- Either.cond(shardCount > 0, (), s"seed.shard-count must be positive, got $shardCount")
      _ <- config.classes
        .map(_.name)
        .foldLeft[Either[String, Unit]](Right(())): (acc, name) =>
          acc.flatMap(_ => activityClassOf(name).map(_ => ()))
      _ <- Either.cond(
        config.classes.map(_.name).distinct.size == config.classes.size,
        (),
        "population.classes names a cohort twice",
      )
      _ <- sharesSumToOne("classes", activityShares(config))
      _ <- sharesSumToOne("platform", platformShares(config))
      _ <- sharesSumToOne("credentials", credentialShares(config))
      _ <- sharesSumToOne("roles", roleShares(config))
    yield ()

  /** HOCON names the activity cohorts as free text (`population.classes[].name`) while the store
    * codes them as an enum, so the two have to be reconciled somewhere. Here, by exact name, and
    * failing on anything else: a typo that fell back to a default would change the campaign's
    * session volume with nothing to show for it.
    */
  def activityClassOf(name: String): Either[String, ActivityClass] =
    ActivityClass.values
      .find(_.toString.equalsIgnoreCase(name))
      .toRight(
        s"population.classes has an unknown class '$name' " +
          s"(expected one of: ${ActivityClass.values.map(_.toString.toLowerCase).mkString(", ")})",
      )

  private def activityShares(config: PopulationConfig): List[(ActivityClass, Double)] =
    config.classes.flatMap(cohort => activityClassOf(cohort.name).toOption.map(_ -> cohort.share))

  private def platformShares(config: PopulationConfig): List[(Platform, Double)] =
    List(Platform.Mobile -> config.platform.mobile, Platform.Web -> config.platform.web)

  private def credentialShares(config: PopulationConfig): List[(CredentialKind, Double)] =
    List(
      CredentialKind.Otp -> config.credentials.otp,
      CredentialKind.OtpPassword -> config.credentials.otpPassword,
      CredentialKind.Passkey -> config.credentials.passkey,
    )

  private def roleShares(config: PopulationConfig): List[(UserRole, Double)] =
    List(UserRole.RetailUser -> config.roles.retailUser, UserRole.RetailBasic -> config.roles.retailBasic)

  /** Picks a cohort by where `hash(id, salt)` falls in the cumulative shares. The last cohort
    * absorbs floating-point rounding, which is safe only because [[validate]] has already
    * established that the shares sum to 1.
    */
  private def draw[A](id: Long, salt: Long, shares: List[(A, Double)]): A =
    val position = (math.floorMod(mix(id, salt), Buckets).toDouble + 0.5) / Buckets
    var cumulative = 0.0
    val iterator = shares.iterator
    var chosen = shares.last._1
    var decided = false
    while iterator.hasNext && !decided do
      val (value, share) = iterator.next()
      cumulative += share
      if position < cumulative then
        chosen = value
        decided = true
    chosen

  /** SplitMix64's finaliser over `id` and a per-cohort salt. Chosen for one property: the low
    * bits of the output must not depend on the low bits of the input, because the low bits of
    * the input are the shard. A different salt per cohort makes the four draws independent, so
    * (say) the passkey cohort is not systematically also the heavy cohort.
    */
  private def mix(id: Long, salt: Long): Long =
    var z = id * 0x9e3779b97f4a7c15L + salt
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)

  private val ActivitySalt = 0x5ca1ab1e00000001L
  private val PlatformSalt = 0x5ca1ab1e00000002L
  private val CredentialSalt = 0x5ca1ab1e00000003L
  private val RoleSalt = 0x5ca1ab1e00000004L

  /** 10,000 buckets resolves a share to 0.01%, one order of magnitude finer than the smallest
    * share the design doc's mixes use (`retail-basic` at 10%, `dormant` at 10%).
    */
  private val Buckets = 10_000L

  /** Shares are written as two-decimal literals in HOCON, so they can miss 1.0 by a few units in
    * the last place after summation. Anything looser would let a genuinely wrong mix through.
    */
  private val ShareTolerance = 1e-6
