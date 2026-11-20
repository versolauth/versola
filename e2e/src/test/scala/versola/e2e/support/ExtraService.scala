package versola.e2e.support

import zio.*

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** A second copy of a staged service, started from the same build and `env.conf` as the running one, on ports of
  * its own and against the same database, with a few configuration lines of the spec's own appended.
  *
  * For the specs whose subject is a service that has to be stopped and started, or run with a setting the shared
  * stack does not have: the staged stack keeps running as it is. Not for anything that has to leave the running
  * services alone in the database -- both write to it.
  */
object ExtraService:
  private val StartTimeout = 4.minutes

  /** Asked on a connection of its own each time, not through the suite's `Client`. That client answered "connection
    * refused" for twenty seconds after the first refusal to a port -- the service had been listening since the second
    * of them and was ready by the seventh -- so every start of a second service was seen 22 s in, whatever it took. */
  private val probe: java.net.http.HttpClient =
    java.net.http.HttpClient.newBuilder().nn.connectTimeout(java.time.Duration.ofSeconds(2)).nn.build().nn

  /** Whether `readinessUrl` answers 2xx now; false for a refusal, a timeout or any other answer. */
  def isReady(readinessUrl: String): UIO[Boolean] =
    ZIO.attemptBlocking {
      val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(readinessUrl)).nn
        .timeout(java.time.Duration.ofSeconds(2)).nn.GET().nn.build().nn
      val status = probe.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).nn.statusCode()
      status >= 200 && status < 300
    }.catchAll(_ => ZIO.succeed(false))

  /** How often readiness is asked: a start is a few seconds, and the wait for it is in every spec that starts a service. */
  val ReadinessPoll: Duration = 250.millis

  /** Runs `effect` and says how long it took, so that a run's log shows what a second service costs to start and stop. */
  def timed[R, E, A](what: String)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    effect.timed.flatMap: (took, result) =>
      Console.printLine(f"[e2e] $what after ${took.toMillis / 1000.0}%.1f s").ignore.as(result)

  /** @param readinessUrl the diagnostics port's `/readiness`. */
  case class Running(process: Process, log: Path, readinessUrl: String, name: String):
    def isAlive: Task[Boolean] = ZIO.attemptBlocking(process.isAlive)

    def output: Task[String] = ZIO.attemptBlocking(Files.readString(log))

  /** Starts `launcher` and stops it when the scope closes. `ports` are the environment variables the service reads
    * its listeners from (`PORT`, `DPORT`, `MPORT`, ...), and `DPORT` is where readiness is asked. */
  def start(launcher: String, envConf: String, extraConfig: String, ports: Map[String, String]): ZIO[Scope, Throwable, Running] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking:
        val conf = Files.createTempFile("e2e-extra-", ".conf").nn
        val log = Files.createTempFile("e2e-extra-", ".log").nn
        Files.writeString(conf, Files.readString(Path.of(envConf)) + "\n" + extraConfig + "\n")
        val builder = ProcessBuilder(launcher, s"-Denv.path=$conf")
          .redirectErrorStream(true)
          .redirectOutput(log.toFile)
        ports.foreach((name, port) => builder.environment().nn.put(name, port))
        builder.environment().nn.put("RUN_MIGRATIONS", "false")
        // As the staged stack does (ci-cd.yml): see ConfigSnapshotColdStartSpec.
        builder.environment().nn.putIfAbsent("HTTP_CLIENT_POOL_SIZE", "10")
        (builder.start().nn, conf, log),
    )((process, conf, log) => stopProcess(process).ignore *> ZIO.attemptBlocking(Files.deleteIfExists(conf) && Files.deleteIfExists(log)).ignore)
      .map((process, _, log) => Running(process, log, s"http://localhost:${ports("DPORT")}/readiness", Path.of(launcher).getFileName.toString))

  /** Stops it the way an orchestrator does, SIGTERM and then, after a while, SIGKILL. */
  def stop(running: Running): Task[Unit] = stopProcess(running.process)

  private def stopProcess(process: Process): Task[Unit] =
    ZIO.attemptBlocking(process.isAlive).flatMap: alive =>
      timed("extra service stopped")(ZIO.attemptBlocking {
        process.destroy()
        if !process.waitFor(30, TimeUnit.SECONDS) then process.destroyForcibly()
        ()
      }).when(alive).unit

  /** Waits until it answers readiness, or fails with what it logged if it stops or never gets there. */
  def awaitReady(running: Running): Task[Unit] =
    timed(s"${running.name} ready"):
      (isReady(running.readinessUrl) <*> running.isAlive.map(!_))
        .repeat(Schedule.spaced(ReadinessPoll) *> Schedule.recurUntil[(Boolean, Boolean)]((r, stopped) => r || stopped))
        .timeout(StartTimeout)
        .flatMap:
          case Some((true, _)) => ZIO.unit
          case _ => running.output.flatMap(out => ZIO.fail(RuntimeException(s"did not become ready:\n${out.takeRight(4000)}")))
