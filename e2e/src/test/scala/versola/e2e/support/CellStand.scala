package versola.e2e.support

import zio.*
import zio.http.{Client, Request, Status, URL}
import zio.json.ast.Json

import java.nio.file.{Files, Path}
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/** A second cell beside the staged `auth` (versolauth/versola#604): another `auth` of the same build, with its own
  * database and its own `cell-id`, and both cells registered in central so that the staged edge routes by them.
  *
  * What a cell is, is a database of its own: the second `auth` is started on the running one's `env.conf` with the
  * database, the cell id and the address of its mutual-TLS listener replaced, and it migrates the database it is given.
  * Central, the signing keys and the clients are the ones of the stack, so a token of either cell verifies everywhere.
  *
  * The registration changes how the staged edge reaches `auth` for every spec, so it lasts for the scope only: the
  * cells are removed and the edge is told again when it closes. Cell 2 is registered closed: nothing is placed in it
  * by the edge, and a spec reaches it by its own address or by a value that names it. The suite runs its specs one
  * after another (`build.sbt`), which is what makes this safe.
  *
  * @param first the staged `auth`, as cell [[E2EConfig.authCellId]]
  * @param second the cell this stand started
  * @param setups email-registration clients registered before the second cell started, so that it knows them from its
  *   first read of the configuration; each has an address nobody registered
  */
final case class CellStand(first: CellStand.Cell, second: CellStand.Cell, private val setups: Ref[List[Flows.Setup]]):

  /** A registration client and an address nobody has used. */
  def nextRegistration: Task[Flows.Setup] =
    setups.modify:
      case head :: tail => (ZIO.succeed(head), tail)
      case Nil => (ZIO.fail(RuntimeException("CellStand ran out of registration setups; raise CellStand.Setups")), Nil)
    .flatten

object CellStand:
  val SecondCellId = "local-2"

  /** How many registration setups are made up front. */
  val Setups = 6

  /** @param auth the cell reached by its own address, as nothing in front of it would route */
  final case class Cell(id: String, auth: OAuthClient, url: String, mutualTlsUrl: String)

  private val Port = 9051
  private val DiagnosticsPort = 9052
  private val AdditionalPort = 9057
  private val MutualTlsPort = 9058

  private val StartTimeout = 4.minutes

  private val Partition = "e2e-stand"
  private val Region = "e2e-stand-region"

  /** `postgres { url, user, password }` of the running auth's `env.conf`: the server the second cell's database is made on. */
  private case class Database(url: String, user: String, password: String):
    /** The same server and options, another database. */
    def named(database: String): String =
      val (base, query) = url.span(_ != '?')
      base.take(base.lastIndexOf('/') + 1) + database + query

    def name: String = url.takeWhile(_ != '?').drop(url.takeWhile(_ != '?').lastIndexOf('/') + 1)

  private def databaseOf(envConf: String): Task[Database] =
    ZIO.attemptBlocking(Files.readString(Path.of(envConf))).flatMap: text =>
      val block = text.drop(text.indexOf("\npostgres {"))
      def value(key: String) = s"""(?m)^\\s*$key\\s*=\\s*"([^"]*)"""".r.findFirstMatchIn(block).map(_.group(1))
      ZIO.fromOption(for url <- value("url"); user <- value("user"); password <- value("password") yield Database(url, user, password))
        .orElseFail(RuntimeException(s"no postgres block with url, user and password in $envConf"))

  /** A database of the second cell's own, empty: the cell migrates it when it starts. Dropped first, so that a run
    * starts from nothing whatever an earlier one left. */
  private def freshDatabase(server: Database, name: String): Task[Unit] =
    ZIO.attemptBlocking:
      val connection = DriverManager.getConnection(server.url, server.user, server.password).nn
      try
        val statement = connection.createStatement().nn
        try
          statement.execute(s"DROP DATABASE IF EXISTS $name WITH (FORCE)")
          statement.execute(s"CREATE DATABASE $name")
        finally statement.close()
      finally connection.close()
    .unit

  private def launch(config: E2EConfig, database: String): ZIO[Scope, Throwable, (Process, Path)] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking:
        val conf = Files.createTempFile("e2e-cell-", ".conf").nn
        val log = Files.createTempFile("e2e-cell-", ".log").nn
        // Appended, so each replaces what the running auth's file says: a later definition of a key wins.
        val overrides =
          s"""
             |cell-id = "$SecondCellId"
             |postgres.url = "$database"
             |mutual-tls.external-url = "https://localhost:$MutualTlsPort"
             |""".stripMargin
        Files.writeString(conf, Files.readString(Path.of(config.authEnvConf)) + overrides)
        // From the repository root, as the staged auth is started: a service finds its migrations under the directory
        // it runs in, and this one has a database to migrate.
        val root = Path.of(config.authEnvConf).toAbsolutePath.nn.normalize.nn.getParent.nn.getParent.nn.getParent.nn
        val launcher = Path.of(config.authLauncher).toAbsolutePath.nn.normalize.nn.toString
        val builder = ProcessBuilder(launcher, s"-Denv.path=$conf").directory(root.toFile).redirectErrorStream(true).redirectOutput(log.toFile)
        Map("PORT" -> Port, "DPORT" -> DiagnosticsPort, "APORT" -> AdditionalPort, "MPORT" -> MutualTlsPort)
          .foreach((key, value) => builder.environment().nn.put(key, value.toString))
        // Its database is empty: this is the one instance of the suite that migrates.
        builder.environment().nn.put("RUN_MIGRATIONS", "true")
        builder.environment().nn.putIfAbsent("HTTP_CLIENT_POOL_SIZE", "10")
        (builder.start().nn, conf, log),
    )((process, conf, log) =>
      ZIO.attemptBlocking {
        process.destroy()
        if !process.waitFor(30, TimeUnit.SECONDS) then process.destroyForcibly()
        Files.deleteIfExists(conf)
        Files.deleteIfExists(log)
      }.ignore,
    ).map((process, _, log) => (process, log))

  private def awaitReady(process: Process, log: Path, client: Client): Task[Unit] =
    val ready = ZIO.scoped(client.request(Request.get(URL.decode(s"http://localhost:$DiagnosticsPort/readiness").toOption.get)))
      .map(_.status.isSuccess).catchAll(_ => ZIO.succeed(false))
    (ready <*> ZIO.attemptBlocking(!process.isAlive))
      .repeat(Schedule.spaced(1.second) *> Schedule.recurUntil[(Boolean, Boolean)]((r, stopped) => r || stopped))
      .timeout(StartTimeout)
      .flatMap:
        case Some((true, _)) => ZIO.unit
        case _ =>
          ZIO.attemptBlocking(Files.readString(log)).flatMap(out =>
            ZIO.fail(RuntimeException(s"the second cell did not become ready:\n${out.takeRight(4000)}")),
          )

  private def cell(id: String, internalUrl: String, mutualTlsUrl: String, pin: String, state: String, weight: Int): Json.Obj =
    Json.Obj(
      "id" -> Json.Str(id),
      "regionId" -> Json.Str(Region),
      "internalUrl" -> Json.Str(internalUrl),
      "mtlsUrl" -> Json.Str(mutualTlsUrl),
      "trustedCertificates" -> Json.Arr(Json.Str(pin)),
      "state" -> Json.Str(state),
      "weight" -> Json.Num(weight),
    )

  private def accepted(what: String, result: ApiResult, also: Status*): Task[Unit] =
    ZIO.fail(RuntimeException(s"$what refused: ${result.status} ${result.body}"))
      .unless(result.status.isSuccess || also.contains(result.status)).unit

  /** Registers both cells and tells the staged edge; removes them and tells it again when the scope closes. */
  private def registered(central: CentralApi, edge: EdgeApi, first: Json.Obj, second: Json.Obj): ZIO[Scope, Throwable, Unit] =
    def closed(cell: Json.Obj) = Json.Obj(cell.fields.map((key, value) => if key == "state" then key -> Json.Str("closed") else key -> value))
    def id(cell: Json.Obj) = cell.get("id").flatMap(_.asString).getOrElse("")
    // What an earlier run that was stopped left: the same removal as at the end, and nothing to remove is not an error.
    val remove =
      ZIO.foreachDiscard(List(first, second))(cell =>
        (central.put("/configuration/cells", closed(cell)) *> central.delete("/configuration/cells", "cellId" -> id(cell))).ignore,
      ) *> central.delete("/configuration/regions", "regionId" -> Region).ignore *> edge.syncConfiguration.ignore
    ZIO.acquireRelease(
      for
        _ <- remove
        partition <- central.post("/configuration/partitions", Json.Obj("id" -> Json.Str(Partition)))
        _ <- accepted("the stand's partition", partition, Status.Conflict)
        region <- central.post("/configuration/regions", Json.Obj("id" -> Json.Str(Region), "partitionId" -> Json.Str(Partition)))
        _ <- accepted("the stand's region", region)
        one <- central.post("/configuration/cells", first)
        _ <- accepted("the first cell", one)
        two <- central.post("/configuration/cells", second)
        _ <- accepted("the second cell", two)
        _ <- edge.syncConfiguration
      yield (),
    )(_ => remove)

  val layer: ZLayer[E2EConfig & Client & OAuthClient & CentralApi & EdgeApi, Throwable, CellStand] =
    ZLayer.scoped:
      for
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        oauth <- ZIO.service[OAuthClient]
        central <- ZIO.service[CentralApi]
        edge <- ZIO.service[EdgeApi]
        // Before the second cell starts: it reads the configuration once at start-up and then on its refresh interval.
        setups <- ZIO.foreach(List.range(0, Setups))(_ => Flows.setupEmailRegistration().provideEnvironment(ZEnvironment(oauth)))
        server <- databaseOf(config.authEnvConf)
        database = s"${server.name}_cell2"
        _ <- freshDatabase(server, database)
        (process, log) <- launch(config, server.named(database))
        _ <- awaitReady(process, log, client)
        // The leaf of auth's own listener, which is what an edge pins a cell by; both cells present the same one.
        pin <- ZIO.attemptBlocking(Files.readString(Path.of(config.authMutualTlsTrustedCertificates).resolveSibling("server.crt")).trim)
        secondUrl = s"http://localhost:$Port"
        secondMutualTlsUrl = s"https://localhost:$MutualTlsPort"
        _ <- registered(
          central,
          edge,
          first = cell(config.authCellId, config.authUrl, config.authMutualTlsUrl, pin, state = "open", weight = 100),
          second = cell(SecondCellId, secondUrl, secondMutualTlsUrl, pin, state = "closed", weight = 0),
        )
        unused <- Ref.make(setups)
      yield CellStand(
        first = Cell(config.authCellId, oauth, config.authUrl, config.authMutualTlsUrl),
        second = Cell(
          SecondCellId,
          OAuthClient(client, config.copy(authUrl = secondUrl, authCellId = SecondCellId)),
          secondUrl,
          secondMutualTlsUrl,
        ),
        setups = unused,
      )
