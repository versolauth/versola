package versola.loadgen.seed

import versola.loadgen.config.*
import versola.loadgen.model.*
import versola.loadgen.store.StoreCodes
import zio.test.*

/** The `COPY` encoding, away from a database.
  *
  * `SeederSmokeSpec` already proves the rows land correctly in real tables, which is the stronger
  * statement -- so what is here is the part a database round trip cannot tell you: that a field
  * count matches its declared column count, and that the CSV escaping rules hold for values the
  * seeder does not currently generate but a future mix might.
  */
object SeedRowsSpec extends ZIOSpecDefault:

  private val population = PopulationConfig(
    target = 1_000L,
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

  private val now = java.time.Instant.parse("2026-09-14T09:00:00Z")

  private def seeded(id: Long): SeededUser =
    SeededUser(PopulationPlan.userOf(population, 8, id), None, None)

  /** Counts CSV fields the way Postgres' reader does: commas outside quotes. */
  private def fields(row: String): Int =
    var count = 1
    var quoted = false
    var index = 0
    while index < row.length do
      val char = row.charAt(index)
      if char == '"' then quoted = !quoted
      else if char == ',' && !quoted then count += 1
      index += 1
    count

  def spec = suite("SeedRows")(
    // Field count against declared column count, per table. A row one field short is a `COPY`
    // that fails loudly; a row with two same-typed fields transposed is a `COPY` that succeeds
    // and an unusable population, and the count is the cheap half of guarding against it. The
    // expensive half is SeederSmokeSpec reading the values back.
    test("every rendered row has exactly as many fields as SutSchema declares columns") {
      val user = seeded(1L)
      val password = HashedPassword(1L, versola.util.Salt(Array.fill(16)(1.toByte)), versola.util.MAC(Array.fill(32)(2.toByte)))
      val passkey = PasskeyMaterial(Array.fill(32)(3.toByte), versola.util.Secret(Array.fill(64)(4.toByte)), Array.fill(77)(5.toByte))
      assertTrue(
        fields(SeedRows.users(user)) == SutSchema.users.columns.size,
        fields(SeedRows.userPasswords(user, password, now)) == SutSchema.userPasswords.columns.size,
        fields(SeedRows.userRoles(user, "default")) == SutSchema.userRoles.columns.size,
        fields(SeedRows.passkeys(user, passkey, now)) == SutSchema.passkeys.columns.size,
        fields(SeedRows.userIndex(user)) == SutSchema.userIndex.columns.size,
        fields(SeedRows.vuUsers(user)) == 13,
      )
    },
    test("the COPY statement names the declared columns, in the declared order") {
      assertTrue(
        SutSchema.copyStatement(SutSchema.users) ==
          "COPY users (id, phone, claims) FROM STDIN WITH (FORMAT csv, NULL '')",
        SutSchema.copyStatement(SutSchema.userIndex) ==
          "COPY user_index (id, phone) FROM STDIN WITH (FORMAT csv, NULL '')",
      )
    },
    // NULL is the only unquoted field, and that is the entire NULL protocol: Postgres' CSV reader
    // reads an unquoted empty field as NULL and a quoted one as the empty string. Quoting
    // unconditionally is what stops a value that happens to be empty from silently becoming NULL.
    test("NULL is the only unquoted field, and the empty string is not NULL") {
      assertTrue(
        CopyRow.empty.nullValue().text("x").render == ",\"x\"",
        CopyRow.empty.text("").render == "\"\"",
        CopyRow.empty.optionalText(None).optionalText(Some("")).render == ",\"\"",
      )
    },
    test("a quote inside a value is doubled, not escaped with a backslash") {
      assertTrue(
        CopyRow.empty.text("a\"b").render == "\"a\"\"b\"",
        // A backslash survives verbatim, which is the reason for CSV over the text format: bytea
        // goes out as `\x...` and must not be de-escaped on the way in.
        CopyRow.empty.text("a\\b").render == "\"a\\b\"",
      )
    },
    test("bytea is Postgres' hex input format, lower case and unpadded") {
      assertTrue(
        CopyRow.empty.bytes(Array(0x00, 0x0f, 0x7f, 0xff).map(_.toByte)).render == "\"\\x000f7fff\"",
        CopyRow.empty.bytes(Array.emptyByteArray).render == "\"\\x\"",
        CopyRow.empty.optionalBytes(None).render == "",
      )
    },
    test("a text[] quotes its elements, so a comma or brace inside one cannot end the array") {
      assertTrue(
        CopyRow.empty.textArray(List("Internal")).render == "\"{\"\"Internal\"\"}\"",
        CopyRow.empty.textArray(List("a,b", "c}d")).render == "\"{\"\"a,b\"\",\"\"c}d\"\"}\"",
        CopyRow.empty.textArray(Nil).render == "\"{}\"",
      )
    },
    test("booleans and timestamps are in the forms Postgres parses without a session setting") {
      assertTrue(
        CopyRow.empty.boolean(true).boolean(false).render == "\"t\",\"f\"",
        // ISO-8601 with an explicit Z. A local-looking timestamp would be read in the server's
        // TimeZone, not the seeder's.
        CopyRow.empty.instant(now).render == "\"2026-09-14T09:00:00Z\"",
        CopyRow.empty.optionalInstant(None).render == "",
      )
    },
    // `vu_users` enum columns go through StoreCodes rather than `ordinal`, for the reason that
    // object exists: a case inserted into the middle of an enum must not reinterpret rows a
    // previous campaign wrote.
    test("vu_users enum columns carry StoreCodes' codes, not ordinals") {
      val user = PopulationPlan.userOf(population, 8, 1L).copy(
        activityClass = ActivityClass.Dormant,
        platform = Platform.Web,
        credential = CredentialKind.Passkey,
        role = UserRole.RetailBasic,
      )
      val rendered = SeedRows.vuUsers(SeededUser(user, None, None)).split(',').toList
      assertTrue(
        rendered(4) == s""""${StoreCodes.activityClass.encode(ActivityClass.Dormant)}"""",
        rendered(5) == s""""${StoreCodes.platform.encode(Platform.Web)}"""",
        rendered(6) == s""""${StoreCodes.credential.encode(CredentialKind.Passkey)}"""",
        rendered(7) == s""""${StoreCodes.role.encode(UserRole.RetailBasic)}"""",
        rendered(10) == s""""${StoreCodes.userState.encode(VirtualUserState.Registered)}"""",
      )
    },
    // `PostgresPasskeyRepository` reads these back with `CredentialDeviceType.valueOf` and
    // `AuthenticatorTransport.valueOf`, so they are the Scala case names, not WebAuthn's
    // `single-device`/`internal`. Getting it wrong throws on the read, long after the write.
    test("passkey enum columns are spelled the way auth's valueOf reads them") {
      val passkey = PasskeyMaterial(Array.fill(32)(3.toByte), versola.util.Secret(Array.fill(64)(4.toByte)), Array.fill(77)(5.toByte))
      val rendered = SeedRows.passkeys(seeded(1L), passkey, now)
      assertTrue(
        rendered.contains("\"SingleDevice\""),
        rendered.contains("\"{\"\"Internal\"\"}\""),
        !rendered.contains("single-device"),
        !rendered.contains("internal\""),
      )
    },
    // The role id comes from CampaignBlueprint's constants, which is also where the provisioner
    // takes it -- there is no foreign key from auth to central, so a seeder with its own copy
    // could grant a role that does not exist and produce a population that logs in and then 403s
    // on everything.
    test("the granted role id is the provisioner's, not a second copy of it") {
      import versola.loadgen.provision.CampaignBlueprint
      assertTrue(
        SeedRows.userRole(PopulationPlan.userOf(population, 8, 1L).copy(role = UserRole.RetailUser)) ==
          CampaignBlueprint.retailUserRoleId,
        SeedRows.userRole(PopulationPlan.userOf(population, 8, 1L).copy(role = UserRole.RetailBasic)) ==
          CampaignBlueprint.retailBasicRoleId,
      )
    },
    // Defence in depth against a `COPY` that reports fewer rows than it was given. Postgres
    // either accepts the whole stream or raises, so this is not a case anything here can produce
    // naturally -- which is exactly why it is asserted against a stub: a short `COPY` is silent
    // data loss, and `copyIn` alone does not report one.
    test("a COPY that accepts fewer rows than it was sent fails rather than being ignored") {
      val short = new CopySink:
        def copyIn(statement: String, rows: zio.Chunk[String]): zio.Task[Long] = zio.ZIO.succeed(rows.size - 1L)
        def execute(statement: String): zio.Task[Unit] = zio.ZIO.unit
        def atomically[A](effect: zio.Task[A]): zio.Task[A] = effect

      val writer = SutWriter(short, short)
      writer
        .write(zio.Chunk(seeded(1L), seeded(2L)), "default", now, 1L, 3L)
        .exit
        .map: exit =>
          assertTrue(
            exit.isFailure,
            exit.causeOption.exists(_.failures.exists:
              case ShortCopy("users", 2L, 1L) => true
              case _ => false,
            ),
          )
    },
    suite("batch ranges")(
      test("cover the whole id range exactly once, with a short final batch") {
        val batches = Seeder.batches(1L, 1_000L, 250)
        assertTrue(
          batches == List((1L, 251L), (251L, 501L), (501L, 751L), (751L, 1001L)),
          batches.flatMap((from, until) => from until until) == (1L to 1_000L).toList,
        )
      },
      test("a resumed range starts where it left off and still ends at the target") {
        val batches = Seeder.batches(751L, 1_000L, 250)
        assertTrue(batches == List((751L, 1001L)))
      },
      test("a target not divisible by the batch size ends short rather than over") {
        val batches = Seeder.batches(1L, 10L, 4)
        assertTrue(batches == List((1L, 5L), (5L, 9L), (9L, 11L)))
      },
      test("a fully seeded population yields no batches at all") {
        assertTrue(Seeder.batches(1_001L, 1_000L, 250).isEmpty)
      },
    ),
  )
