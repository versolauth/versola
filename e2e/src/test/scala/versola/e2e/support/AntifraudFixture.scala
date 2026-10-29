package versola.e2e.support

import zio.*
import zio.http.{Client, URL}
import zio.json.*
import zio.json.ast.Json

import java.lang.System as JavaSystem
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, Timestamp}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One of the auth instances an antifraud spec starts: the way to talk to it (`auth`, which reaches it by its own
  * `authUrl`) and what it has written to its log.
  *
  * The `antifraud` object of the `receive-http` line is what the specs read the decision from: the request's check
  * goes on that line and nowhere else, so there is nothing else to look at.
  */
final case class AntifraudInstance(name: String, auth: OAuthClient, diagnosticsUrl: String, log: Path):

  /** The `receive-http` lines written so far that carry an antifraud check, oldest first. */
  def decisions: Task[List[AntifraudInstance.Decision]] =
    ZIO.attemptBlocking(Files.readAllLines(log).nn.toArray.toList.map(_.toString)).map(_.flatMap(AntifraudInstance.decision))

object AntifraudInstance:

  /** The antifraud check of one request, as its `receive-http` line says it. */
  final case class Decision(
      path: String,
      ip: Option[String],
      action: String,
      decision: String,
      score: Int,
      rules: List[String],
      outcome: Option[String],
  )

  private def decision(line: String): Option[Decision] =
    for
      json <- line.fromJson[Json.Obj].toOption
      if json.get("message").exists(_ == Json.Str("receive-http"))
      antifraud <- json.get("antifraud").collect { case o: Json.Obj => o }
      text = (obj: Json.Obj, key: String) => obj.get(key).collect { case Json.Str(s) => s }
      decision <- text(antifraud, "decision")
    yield Decision(
      path = json.get("http").collect { case o: Json.Obj => o }.flatMap(_.get("request")).collect { case o: Json.Obj => o }
        .flatMap(text(_, "path")).getOrElse(""),
      ip = json.get("auth").collect { case o: Json.Obj => o }.flatMap(text(_, "ip")),
      action = text(antifraud, "action").getOrElse(""),
      decision = decision,
      score = antifraud.get("score").collect { case Json.Num(n) => n.intValue }.getOrElse(0),
      outcome = text(antifraud, "outcome"),
      rules = antifraud.get("rules").collect { case Json.Arr(items) => items.toList.collect { case Json.Str(s) => s } }.getOrElse(Nil),
    )

/** Two auth instances of the staged build, on one database, with the antifraud decision, the velocity counters and the
  * lists on. Whether a code was sent is the `outcome` of the request's check (`sent`, `faked`, `blocked`): outside
  * production no provider is called, so there is no SMS to see.
  *
  * They are started the way [[versola.e2e.flows.central.ConfigSnapshotColdStartSpec]] starts its second auth: from the
  * staged launcher on the running one's `env.conf` with a block appended, on ports of their own. The running stack is
  * left as it is (antifraud off); central is the one it uses, so a client registered there before the instances start
  * is known to them.
  *
  * @param setups phone sign-in clients and the users that belong to them, registered before the instances start
  *               (they read the configuration once, then every `configuration-cache-refresh-interval`), one for
  *               each case that needs a number of its own: velocity counts a number, so cases do not share one
  */
final case class AntifraudFixture(a: AntifraudInstance, b: AntifraudInstance, private val unused: Ref[List[Flows.Setup]]):

  /** A client and user nobody has used yet. */
  def nextSetup: Task[Flows.Setup] =
    unused.modify:
      case head :: tail => (ZIO.succeed(head), tail)
      case Nil => (ZIO.fail(RuntimeException("AntifraudFixture ran out of setups; raise AntifraudFixture.Setups")), Nil)
    .flatten

object AntifraudFixture:

  /** How many setups are registered up front. */
  val Setups = 20

  /** Limit on codes to one number in an hour (a request over it is refused), and on requests from one address. Low
    * enough for a spec to reach, high enough that the cases of a spec that each use a number of their own do not. */
  val PhoneLimitPerHour = 10
  val IpLimitPerHour = 4

  /** The flush and load intervals of the velocity counters: what the cross-instance case waits for. */
  val SyncInterval = 500.millis

  private val StartTimeout = 4.minutes

  private case class Ports(port: Int, diagnostics: Int, additional: Int, mutualTls: Int)
  private val APorts = Ports(9021, 9022, 9027, 9028)
  private val BPorts = Ports(9023, 9024, 9029, 9030)

  /** Appended to the running auth's `env.conf`, which has the whole `antifraud` block (every key is required; see gen-env), so these only change what the specs need. `timeout` is lengthened from the 20 ms a production evaluation is held
    * to, so that a busy CI machine does not turn a decision into `antifraud_error` (a fail-open `Challenge`). */
  private def antifraudConfig(sync: Duration): String =
    s"""
       |antifraud {
       |  decision { enabled = true, timeout = "5 seconds" }
       |  tuning { refresh-interval = "1 second" }
       |  velocity {
       |    enabled = true
       |    flush-interval = "${sync.toMillis} millis"
       |    load-interval = "${sync.toMillis} millis"
       |  }
       |  # The thresholds are not configuration: `tuned` writes them to the row of af_settings.
       |  lists {
       |    enabled = true
       |    refresh-interval = "1 second"
       |  }
       |}
       |""".stripMargin

  private case class Started(process: Process, log: Path, instance: AntifraudInstance)

  private def launch(name: String, config: E2EConfig, client: Client, ports: Ports): ZIO[Scope, Throwable, AntifraudInstance] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking:
        val conf = Files.createTempFile(s"e2e-af-$name-", ".conf").nn
        val log = Files.createTempFile(s"e2e-af-$name-", ".log").nn
        Files.writeString(conf, Files.readString(Path.of(config.authEnvConf)) + antifraudConfig(SyncInterval))
        val builder = ProcessBuilder(config.authLauncher, s"-Denv.path=$conf")
          .redirectErrorStream(true)
          .redirectOutput(log.toFile)
        Map(
          "PORT" -> ports.port,
          "DPORT" -> ports.diagnostics,
          "APORT" -> ports.additional,
          "MPORT" -> ports.mutualTls,
        ).foreach((key, value) => builder.environment().nn.put(key, value.toString))
        // The running stack has migrated the schema already, and two instances starting migrations at once is not what is under test.
        builder.environment().nn.put("RUN_MIGRATIONS", "false")
        builder.environment().nn.putIfAbsent("HTTP_CLIENT_POOL_SIZE", "10")
        // The specs read decisions from `receive-http` lines, which a sampled or off access log drops for a success.
        builder.environment().nn.put("HTTP_ACCESS_LOG", "full")
        Started(
          builder.start().nn,
          log,
          AntifraudInstance(
            name,
            OAuthClient(client, config.copy(authUrl = s"http://localhost:${ports.port}")),
            s"http://localhost:${ports.diagnostics}",
            log,
          ),
        ) -> conf,
    )((started, conf) =>
      ZIO.attemptBlocking {
        started.process.destroy()
        if !started.process.waitFor(30, TimeUnit.SECONDS) then started.process.destroyForcibly()
        Files.deleteIfExists(conf)
        Files.deleteIfExists(started.log)
      }.ignore,
    ).flatMap: (started, _) =>
      awaitReady(started, client).as(started.instance)

  /** Waits until the instance answers `/readiness`, or fails with what it wrote if it stops or never gets there. */
  private def awaitReady(started: Started, client: Client): Task[Unit] =
    val ready = ZIO.scoped(client.request(zio.http.Request.get(URL.decode(s"${started.instance.diagnosticsUrl}/readiness").toOption.get)))
      .map(_.status.isSuccess).catchAll(_ => ZIO.succeed(false))
    val stopped = ZIO.attemptBlocking(!started.process.isAlive)
    (ready <*> stopped)
      .repeat(Schedule.spaced(1.second) *> Schedule.recurUntil[(Boolean, Boolean)]((r, s) => r || s))
      .timeout(StartTimeout)
      .flatMap:
        case Some((true, _)) => ZIO.unit
        case _ =>
          ZIO.attemptBlocking(Files.readString(started.log)).flatMap(out =>
            ZIO.fail(RuntimeException(s"${started.instance.name} did not become ready:\n${out.takeRight(4000)}")),
          )

  /** Registers the setups, then starts the two instances, which stop with the scope. */
  val layer: ZLayer[E2EConfig & Client & OAuthClient, Throwable, AntifraudFixture] =
    ZLayer.scoped:
      for
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        oauth <- ZIO.service[OAuthClient]
        // Without limits on OTP requests (the default of the suite's tenant, as every spec that changes it restores),
        // so that the only thing that can refuse a second request for a number is the antifraud decision. The
        // instances read the configuration when they start, which is after this.
        //
        // With a limit nothing can reach (1000 in an hour), so that the steps of it a request takes are recorded and can be counted.
        _ <- oauth.upsertChallengeSettings(otpRequestLimits = """[{"maxAttempts":1000,"windowSeconds":3600}]""")
        setups <- ZIO.foreach(List.range(0, Setups))(_ => Flows.setupPhoneOtp().provideEnvironment(ZEnvironment(oauth)))
        _ <- tuned
        // Both at once: each waits for central and loads the configuration. The tuning is stored before they start, which
        // is when they first read it.
        instances <- launch("antifraud-a", config, client, APorts) <&> launch("antifraud-b", config, client, BPorts)
        unused <- Ref.make(setups)
      yield AntifraudFixture(instances._1, instances._2, unused)

  private def database[A](use: Connection => A): Task[A] =
    for
      url <- ZIO.attempt(Option(JavaSystem.getenv("E2E_DB_URL"))).map(_.getOrElse("jdbc:postgresql://localhost:5432/auth"))
      user <- ZIO.attempt(Option(JavaSystem.getenv("E2E_DB_USER"))).map(_.getOrElse("dev"))
      password <- ZIO.attempt(Option(JavaSystem.getenv("E2E_DB_PASSWORD"))).map(_.getOrElse("1234"))
      result <- ZIO.attemptBlocking:
        val connection: Connection = DriverManager.getConnection(url, user, password).nn
        try use(connection)
        finally connection.close()
    yield result

  private def writeSettings(settings: String): Task[Unit] =
    database: connection =>
      connection.setAutoCommit(false)
      val upsert = connection.prepareStatement(
        """INSERT INTO af_settings (id, settings, updated_by, updated_at) VALUES (1, ?::jsonb, 'e2e', now())
          |ON CONFLICT (id) DO UPDATE SET settings = EXCLUDED.settings, updated_by = 'e2e', updated_at = now()""".stripMargin,
      ).nn
      upsert.setString(1, settings)
      upsert.executeUpdate()
      bump(connection)
      connection.commit()

  private def bump(connection: Connection): Unit =
    connection.createStatement().nn.executeUpdate(
      "INSERT INTO af_settings_version (id, version) VALUES (1, 1) ON CONFLICT (id) DO UPDATE SET version = af_settings_version.version + 1",
    )

  private def deleteSettings: Task[Unit] =
    database: connection =>
      connection.setAutoCommit(false)
      connection.createStatement().nn.executeUpdate("DELETE FROM af_settings WHERE id = 1")
      bump(connection)
      connection.commit()

  /** The fixture's thresholds, set the way the admin API would: in the row of `af_settings` and the bump of
    * `af_settings_version` that tells the instances to reload. The row is put back as it was when the fixture goes (or
    * removed, if there was none, which fails the fixture first), so that the running stack, and the next run, find everything off. */
  private val tuned: ZIO[Scope, Throwable, Unit] =
    val limit = (perHour: Int) =>
      Json.Obj(
        "perMinute" -> Json.Num(0),
        "perTenMinutes" -> Json.Num(0),
        "perHour" -> Json.Num(perHour),
        "perDay" -> Json.Num(0),
        "weight" -> Json.Num(50),
      )
    ZIO.acquireRelease(
      database: connection =>
        val rs = connection.createStatement().nn.executeQuery("SELECT settings FROM af_settings WHERE id = 1").nn
        if rs.next() then Some(rs.getString(1).nn) else None,
    )(before => before.fold(deleteSettings)(writeSettings).ignore).flatMap: before =>
      for
        text <- ZIO.fromOption(before).orElseFail(RuntimeException("af_settings has no row: auth's bootstrap writes it when the stack starts"))
        row <- ZIO.fromEither(text.fromJson[Json.Obj]).mapError(RuntimeException(_))
        thresholds = row.get("thresholds").collect { case o: Json.Obj => o }.getOrElse(Json.Obj())
        tuned = row
          .add("challengeAt", Json.Num(15))
          .add("blockAt", Json.Num(50))
          .add("thresholds", thresholds.add("phone", limit(PhoneLimitPerHour)).add("ip", limit(IpLimitPerHour)))
        _ <- writeSettings(tuned.toJson)
      yield ()

  /** What the specs seed the lists with, the way [[versola.antifraud.AntifraudListRepository.upsert]] stores an entry:
    * the row, and the bump of `af_list_version` that tells the instances to reload. There is no endpoint that
    * changes the lists yet, and a row written by hand is otherwise not seen until the instances' full reload (a minute).
    */
  final case class ListEntry(
      listType: String,
      valueType: String,
      value: String,
      expiresAt: Option[Instant] = None,
      shadow: Boolean = false,
  )

  /** How many steps of the limit on codes asked for `credential` have been taken, as the throttle table has them. */
  def otpRequestSteps(credential: String): Task[Int] =
    for
      url <- ZIO.attempt(Option(JavaSystem.getenv("E2E_DB_URL"))).map(_.getOrElse("jdbc:postgresql://localhost:5432/auth"))
      user <- ZIO.attempt(Option(JavaSystem.getenv("E2E_DB_USER"))).map(_.getOrElse("dev"))
      password <- ZIO.attempt(Option(JavaSystem.getenv("E2E_DB_PASSWORD"))).map(_.getOrElse("1234"))
      steps <- ZIO.attemptBlocking:
        val connection: Connection = DriverManager.getConnection(url, user, password).nn
        try
          val query = connection.prepareStatement(
            "SELECT COALESCE(SUM(jsonb_array_length(attempts)), 0) FROM challenge_throttle WHERE subject = ? AND lower(challenge_type) = 'otprequest'",
          ).nn
          query.setString(1, credential)
          val rows = query.executeQuery().nn
          rows.next()
          rows.getInt(1)
        finally connection.close()
    yield steps

  def seed(entries: List[ListEntry]): Task[Unit] =
    for
      url <- ZIO.attempt(Option(JavaSystem.getenv("E2E_DB_URL"))).map(_.getOrElse("jdbc:postgresql://localhost:5432/auth"))
      user <- ZIO.attempt(Option(JavaSystem.getenv("E2E_DB_USER"))).map(_.getOrElse("dev"))
      password <- ZIO.attempt(Option(JavaSystem.getenv("E2E_DB_PASSWORD"))).map(_.getOrElse("1234"))
      now <- Clock.instant
      _ <- ZIO.attemptBlocking:
        val connection: Connection = DriverManager.getConnection(url, user, password).nn
        try
          connection.setAutoCommit(false)
          val insert = connection.prepareStatement(
            """INSERT INTO af_list_entry (id, type, value_type, value, tenant_id, expires_at, reason, source, shadow, created_by, created_at)
              |VALUES (?, ?, ?, ?, NULL, ?, 'e2e', 'e2e', ?, 'e2e', ?)""".stripMargin,
          ).nn
          entries.foreach: entry =>
            insert.setObject(1, UUID.randomUUID())
            insert.setString(2, entry.listType)
            insert.setString(3, entry.valueType)
            insert.setString(4, entry.value)
            insert.setTimestamp(5, entry.expiresAt.map(Timestamp.from).orNull)
            insert.setBoolean(6, entry.shadow)
            insert.setTimestamp(7, Timestamp.from(now))
            insert.executeUpdate()
          connection.createStatement().nn.executeUpdate("UPDATE af_list_version SET version = version + 1 WHERE id = 1")
          connection.commit()
        finally connection.close()
    yield ()
