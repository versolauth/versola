package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Client, URL}
import zio.test.*

import java.nio.file.{Files, Path}
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/** #566: auth and edge start from the configuration snapshot when central is unreachable.
  *
  * The running stack has recorded its snapshot by the time this runs. Each test starts a second
  * auth and a second edge from the staged build, on ports of their own, against the database
  * the running ones use, with central pointed at a port nothing listens on. Without central they
  * load nothing live: whatever they serve comes from the snapshot.
  *
  * Starting takes one full wait for central (`ReloadingCache.DependencyWait`, two minutes) per
  * service, so auth and edge are started together.
  */
object ConfigSnapshotColdStartSpec extends ZIOSpec[OAuthClient & EdgeApi & EdgeFixture & E2EConfig & Client]:

  override val bootstrap: ZLayer[Any, Any, OAuthClient & EdgeApi & EdgeFixture & E2EConfig & Client] =
    val clients = (E2EConfig.live ++ Client.default) >+> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    clients >+> EdgeFixture.layer(
      EdgeFixture.Config(
        resourceId = "e2e-config-snapshot",
        resourceUri = "http://localhost:9120",
        endpoints = List(EdgeFixture.Endpoint(name = "liveness", path = "/liveness")),
      ),
    )

  private val UnreachableCentral = "http://localhost:9"
  private val StartTimeout = 4.minutes

  private val ColdAuthUrl = "http://localhost:9013"
  private val ColdAuthPorts = Map("PORT" -> "9013", "DPORT" -> "9014", "APORT" -> "9017", "MPORT" -> "9018")
  private val ColdEdgeUrl = "http://localhost:9015"
  private val ColdEdgePorts = Map("PORT" -> "9015", "DPORT" -> "9016")

  private case class Started(process: Process, log: Path, readinessUrl: String)

  /** Starts `launcher` on its running counterpart's `env.conf` with central made unreachable,
    * and stops it when the scope closes. */
  private def launch(launcher: String, envConf: String, ports: Map[String, String]): ZIO[Scope, Throwable, Started] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking:
        val conf = Files.createTempFile("e2e-cold-", ".conf").nn
        val log = Files.createTempFile("e2e-cold-", ".log").nn
        Files.writeString(conf, Files.readString(Path.of(envConf)) + s"\ncentral.url = \"$UnreachableCentral\"\n")
        val builder = ProcessBuilder(launcher, s"-Denv.path=$conf")
          .redirectErrorStream(true)
          .redirectOutput(log.toFile)
        ports.foreach((name, port) => builder.environment().nn.put(name, port))
        builder.environment().nn.put("RUN_MIGRATIONS", "false")
        (builder.start().nn, conf, log)
    )((process, conf, log) =>
      ZIO.attemptBlocking {
        process.destroy()
        if !process.waitFor(30, TimeUnit.SECONDS) then process.destroyForcibly()
        Files.deleteIfExists(conf)
        Files.deleteIfExists(log)
      }.ignore,
    ).map((process, _, log) => Started(process, log, s"http://localhost:${ports("DPORT")}/readiness"))

  /** Waits until the service is ready, or fails with its output if it stops or never gets there. */
  private def awaitReady(started: Started): ZIO[Client, Throwable, Unit] =
    val ready = ZIO.serviceWithZIO[Client](client =>
      ZIO.scoped(client.request(zio.http.Request.get(URL.decode(started.readinessUrl).toOption.get)).map(_.status.isSuccess)),
    ).catchAll(_ => ZIO.succeed(false))
    val stopped = ZIO.attemptBlocking(!started.process.isAlive)
    (ready <*> stopped)
      .repeat(Schedule.spaced(2.seconds) *> Schedule.recurUntil[(Boolean, Boolean)]((r, s) => r || s))
      .timeout(StartTimeout)
      .flatMap:
        case Some((true, _)) => ZIO.unit
        case _ => output(started).flatMap(out => ZIO.fail(RuntimeException(s"did not become ready:\n${out.takeRight(4000)}")))

  /** Waits for the service to stop on its own, and answers its exit code and output. */
  private def awaitExit(started: Started): Task[(Option[Int], String)] =
    for
      exited <- ZIO.attemptBlocking(started.process.waitFor(StartTimeout.toSeconds, TimeUnit.SECONDS))
      out <- output(started)
    yield (Option.when(exited)(started.process.exitValue()), out)

  private def output(started: Started): Task[String] = ZIO.attemptBlocking(Files.readString(started.log))

  private def coldServices(config: E2EConfig): ZIO[Scope, Throwable, (Started, Started)] =
    launch(config.authLauncher, config.authEnvConf, ColdAuthPorts) <*>
      launch(config.edgeLauncher, config.edgeEnvConf, ColdEdgePorts)

  /** Replaces the recorded clients sync body of `table` with a forged one for the scope, and
    * puts the original back afterwards. The save time is moved far ahead as well, which the
    * HMAC also covers: the running service only overwrites a record with a newer one, so this
    * keeps it from replacing the forgery with a valid record while the test runs.
    */
  private def forgeClients(table: String): ZIO[Scope, Throwable, Unit] =
    val key = "/configuration/clients/sync"
    def connect[A](f: java.sql.Connection => A): Task[A] =
      for
        url <- System.env("E2E_DB_URL").map(_.getOrElse("jdbc:postgresql://localhost:5432/auth"))
        user <- System.env("E2E_DB_USER").map(_.getOrElse("dev"))
        password <- System.env("E2E_DB_PASSWORD").map(_.getOrElse("1234"))
        result <- ZIO.attemptBlocking:
          val connection = DriverManager.getConnection(url, user, password).nn
          try f(connection)
          finally connection.close()
      yield result
    ZIO.acquireRelease(
      connect: connection =>
        val select = connection.prepareStatement(s"SELECT body, saved_at FROM $table WHERE key = ?").nn
        select.setString(1, key)
        val rows = select.executeQuery().nn
        if !rows.next() then throw RuntimeException(s"no $key record in $table to forge")
        val original = (rows.getBytes(1).nn, rows.getTimestamp(2).nn)
        val update = connection.prepareStatement(
          s"UPDATE $table SET body = ?, saved_at = '9999-01-01T00:00:00Z' WHERE key = ?",
        ).nn
        update.setBytes(1, """{"clients":[]}""".getBytes)
        update.setString(2, key)
        update.executeUpdate()
        original,
    )((body, savedAt) =>
      connect { connection =>
        val restore = connection.prepareStatement(s"UPDATE $table SET body = ?, saved_at = ? WHERE key = ?").nn
        restore.setBytes(1, body)
        restore.setTimestamp(2, savedAt)
        restore.setString(3, key)
        restore.executeUpdate()
      }.ignore,
    ).unit

  def spec = suite("Configuration snapshot: cold start without central")(
    test("auth and edge start from the snapshot and serve a login and a token request") {
      for
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        running <- ZIO.service[OAuthClient]
        f <- ZIO.service[EdgeFixture]
        s <- Flows.setupLoginPassword()
        // The running services record what they load; syncing now puts this test's client and
        // preset into the snapshot before central goes away for the cold ones.
        _ <- running.syncConfiguration()
        _ <- ZIO.serviceWithZIO[EdgeApi](_.syncConfiguration)
        (coldAuth, coldEdge) <- coldServices(config)
        _ <- awaitReady(coldAuth) <&> awaitReady(coldEdge)

        auth = OAuthClient(client, config.copy(authUrl = ColdAuthUrl))
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
        challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(authorize.conversationCookie.get, s.login.get, s.password, challenge.csrf)
          .assertRedirect(auth, authorize.conversationCookie.get)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success

        // The cold edge signs in through the running auth: what is under test is that it knows
        // the preset, the client it acts as and that client's credential without central.
        edge = EdgeApi(client, config.copy(edgeUrl = ColdEdgeUrl))
        session <- edge.browserLogin(running, f.presetId, f.login, f.password)

        authLog <- output(coldAuth)
        edgeLog <- output(coldEdge)
      yield assertTrue(token.accessToken.nonEmpty).label("auth must issue tokens from the snapshot") &&
        assertTrue(session.cookie.nonEmpty).label("edge must complete a login from the snapshot") &&
        assertTrue(authLog.contains("from the configuration snapshot saved at"))
          .label("auth must say that it serves from the snapshot") &&
        assertTrue(edgeLog.contains("from the configuration snapshot saved at"))
          .label("edge must say that it serves from the snapshot")
    },
    test("with a forged snapshot and central unreachable, auth and edge refuse to start") {
      for
        config <- ZIO.service[E2EConfig]
        _ <- forgeClients("auth_config_snapshots") *> forgeClients("edge_config_snapshots")
        (coldAuth, coldEdge) <- coldServices(config)
        exits <- awaitExit(coldAuth).zipWithPar(awaitExit(coldEdge))((a, e) => (a, e))
        ((authExit, authLog), (edgeExit, edgeLog)) = exits
      yield assertTrue(authExit.exists(_ != 0)).label("auth must not start on a forged snapshot") &&
        assertTrue(edgeExit.exists(_ != 0)).label("edge must not start on a forged snapshot") &&
        assertTrue(authLog.contains("failed verification")) &&
        assertTrue(edgeLog.contains("failed verification"))
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(12.minutes)
