package versola.loadgen.seed

import versola.loadgen.config.*
import versola.loadgen.model.*
import versola.loadgen.scheduler.ShardAssignment
import versola.util.Phone
import zio.test.*

/** The population plan's three load-bearing properties, in the order they would hurt if broken:
  * the phones are values the SUT accepts, the cohort shares are the campaign's, and a driver's
  * slice is a representative sample of the population rather than a biased one.
  */
object PopulationPlanSpec extends ZIOSpecDefault:

  private val population = PopulationConfig(
    target = 1_000_000L,
    classes = List(
      PopulationClassConfig("heavy", 0.15, 0.90, 4),
      PopulationClassConfig("regular", 0.45, 0.40, 2),
      PopulationClassConfig("light", 0.30, 0.10, 1),
      PopulationClassConfig("dormant", 0.10, 0.01, 1),
    ),
    platform = PlatformMixConfig(mobile = 0.88, web = 0.12),
    credentials = CredentialMixConfig(otp = 0.35, otpPassword = 0.40, passkey = 0.25),
    roles = RoleMixConfig(retailUser = 0.90, retailBasic = 0.10),
  )

  private val shardCount = 8

  private def sample(count: Int): Vector[VirtualUser] =
    (1L to count.toLong).toVector.map(PopulationPlan.userOf(population, shardCount, _))

  private def share[A](users: Vector[VirtualUser], of: VirtualUser => A, value: A): Double =
    users.count(user => of(user) == value).toDouble / users.size

  def spec = suite("PopulationPlan")(
    // The failure this rules out is the expensive one: 1M users seeded, and every login rejected
    // at the phone field because libphonenumber does not recognise the number. e2e's CentralApi
    // uses this prefix for the same reason, and this is what keeps the two from drifting.
    test("every generated phone is a number libphonenumber accepts") {
      val invalid = (1L to 5_000L).map(PopulationPlan.phoneOf).filter(Phone.parse(_).isLeft)
      assertTrue(invalid.isEmpty)
    },
    test("phones are distinct across the whole supported id range's endpoints") {
      val ids = List(1L, 2L, 99L, 100L, 1_000_000L, PopulationPlan.maxSupportedId)
      val phones = ids.map(PopulationPlan.phoneOf)
      assertTrue(
        phones.distinct.size == ids.size,
        phones.forall(phone => Phone.parse(phone).isRight),
      )
    },
    // Fixed width is not cosmetic: SutWriter.deleteRange relies on the lexicographic order of
    // these strings agreeing with the numeric order of the ids, which is only true while every
    // phone has the same number of digits.
    test("phones are fixed width, so an id range is a contiguous BETWEEN range") {
      val phones = List(1L, 42L, 999_999L, PopulationPlan.maxSupportedId).map(PopulationPlan.phoneOf)
      assertTrue(
        phones.map(_.length).distinct == List(phones.head.length),
        PopulationPlan.phoneOf(1L) < PopulationPlan.phoneOf(2L),
        PopulationPlan.phoneOf(99L) < PopulationPlan.phoneOf(100L),
      )
    },
    test("a user is a total function of its id, so a resumed or repeated seed reproduces it") {
      val once = PopulationPlan.userOf(population, shardCount, 123_456L)
      val again = PopulationPlan.userOf(population, shardCount, 123_456L)
      assertTrue(
        once == again,
        PopulationPlan.sutUserIdOf(123_456L) == PopulationPlan.sutUserIdOf(123_456L),
        PopulationPlan.sutUserIdOf(123_456L) != PopulationPlan.sutUserIdOf(123_457L),
      )
    },
    test("the derived SUT user id is a well-formed RFC 4122 variant-1 version-4 UUID") {
      val ids = (1L to 1_000L).map(PopulationPlan.sutUserIdOf)
      assertTrue(
        ids.forall(_.version() == 4),
        ids.forall(_.variant() == 2),
        ids.distinct.size == ids.size,
      )
    },
    test("shard is id % shardCount, the same function the drivers and coordinator use") {
      val users = sample(1_000)
      assertTrue(users.forall(user => user.shard == ShardAssignment.shardOf(user.id, shardCount)))
    },
    test("only the otp-password cohort carries a password, and it is not blank") {
      val users = sample(2_000)
      assertTrue(
        users.filter(_.credential == CredentialKind.OtpPassword).forall(_.password.exists(_.nonEmpty)),
        users.filterNot(_.credential == CredentialKind.OtpPassword).forall(_.password.isEmpty),
      )
    },
    test("a seeded user is Registered with a SUT identity, because its credentials exist") {
      val users = sample(100)
      assertTrue(
        users.forall(_.state == VirtualUserState.Registered),
        users.forall(_.sutUserId.isDefined),
      )
    },
    // 2% absolute tolerance at 20,000 users: the hashed assignment meets a share to within
    // sampling noise, not exactly, and that trade is stated in PopulationPlan's doc. Loose
    // enough not to be flaky, tight enough that a genuinely wrong mix (a cohort boundary off by
    // one, a salt reused between two draws) fails it.
    test("cohort shares match the configured mix") {
      val users = sample(20_000)
      assertTrue(
        math.abs(share(users, _.credential, CredentialKind.Otp) - 0.35) < 0.02,
        math.abs(share(users, _.credential, CredentialKind.OtpPassword) - 0.40) < 0.02,
        math.abs(share(users, _.credential, CredentialKind.Passkey) - 0.25) < 0.02,
        math.abs(share(users, _.platform, Platform.Mobile) - 0.88) < 0.02,
        math.abs(share(users, _.role, UserRole.RetailBasic) - 0.10) < 0.02,
        math.abs(share(users, _.activityClass, ActivityClass.Heavy) - 0.15) < 0.02,
        math.abs(share(users, _.activityClass, ActivityClass.Dormant) - 0.10) < 0.02,
      )
    },
    // The reason the cohorts are hashed rather than taken from `id % 100`: `shard` is also a
    // modulus of the id, so a modular cohort assignment correlates with it and a driver runs a
    // population that is not the campaign's. Nothing reports that -- every row is individually
    // valid -- so this test is the only thing standing between the mix and a silently skewed
    // campaign.
    test("cohort assignment is independent of the shard a driver owns") {
      val users = sample(40_000)
      val perShard = users.groupBy(_.shard)
      val deviations = perShard.values.toList.map(slice => math.abs(share(slice, _.credential, CredentialKind.Passkey) - 0.25))
      assertTrue(
        perShard.keySet == (0 until shardCount).toSet,
        deviations.forall(_ < 0.03),
      )
    },
    // The same question for the other modulus that matters. A cohort drawn from the same salt as
    // another would make (say) every passkey user also a heavy user, tripling the passkey login
    // rate the campaign generates while leaving both shares individually correct.
    test("the four cohort draws are mutually independent") {
      val users = sample(40_000)
      val passkeys = users.filter(_.credential == CredentialKind.Passkey)
      val webUsers = users.filter(_.platform == Platform.Web)
      assertTrue(
        math.abs(share(passkeys, _.activityClass, ActivityClass.Heavy) - 0.15) < 0.03,
        math.abs(share(passkeys, _.role, UserRole.RetailBasic) - 0.10) < 0.03,
        math.abs(share(webUsers, _.credential, CredentialKind.Passkey) - 0.25) < 0.03,
      )
    },
    suite("validate")(
      test("accepts the design doc's mix") {
        assertTrue(PopulationPlan.validate(population, shardCount, 1_000_000L).isRight)
      },
      // Each of these is a campaign whose generated load is not the configured one, which is
      // indistinguishable from a slow SUT once it is running.
      test("rejects a mix whose shares do not sum to one") {
        assertTrue(
          PopulationPlan.validate(
            population.copy(credentials = CredentialMixConfig(otp = 0.35, otpPassword = 0.40, passkey = 0.10)),
            shardCount,
            1_000L,
          ).isLeft,
          PopulationPlan.validate(
            population.copy(platform = PlatformMixConfig(mobile = 1.0, web = 0.12)),
            shardCount,
            1_000L,
          ).isLeft,
        )
      },
      test("rejects an unknown activity class rather than silently dropping its share") {
        val renamed = population.copy(classes = population.classes.map(_.copy(name = "casual")))
        assertTrue(
          PopulationPlan.validate(renamed, shardCount, 1_000L).isLeft,
          PopulationPlan.activityClassOf("casual").isLeft,
          PopulationPlan.activityClassOf("regular") == Right(ActivityClass.Regular),
        )
      },
      test("rejects a target beyond the phone range, rather than wrapping two users onto one number") {
        assertTrue(
          PopulationPlan.validate(population, shardCount, PopulationPlan.maxSupportedId + 1).isLeft,
          PopulationPlan.validate(population, shardCount, PopulationPlan.maxSupportedId).isRight,
        )
      },
      test("rejects a non-positive target or shard count") {
        assertTrue(
          PopulationPlan.validate(population, shardCount, 0L).isLeft,
          PopulationPlan.validate(population, 0, 1_000L).isLeft,
        )
      },
      test("rejects a cohort named twice, which would double-count its share") {
        val duplicated = population.copy(classes = population.classes :+ PopulationClassConfig("heavy", 0.0, 0.9, 4))
        assertTrue(PopulationPlan.validate(duplicated, shardCount, 1_000L).isLeft)
      },
    ),
  )
