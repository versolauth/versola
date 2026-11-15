package versola.e2e.support

import zio.*
import zio.http.{Client, Request, URL}

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

  /** A start slower than this has its log printed: the staged services are ready in under ten seconds. */
  private val SlowStart = 10.seconds

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
  def awaitReady(running: Running): ZIO[Client, Throwable, Unit] =
    for
      began <- Clock.ClockLive.nanoTime
      // What each ask of readiness got and when, printed if the start was slow: whether the service was late or the asking was.
      polls <- Ref.make(Chunk.empty[String])
      note = (outcome: String) =>
        Clock.ClockLive.nanoTime.flatMap(now => polls.update(_ :+ f"${(now - began) / 1e9}%.1f s: $outcome"))
      ready = ZIO.serviceWithZIO[Client](client =>
        ZIO.scoped(client.request(Request.get(URL.decode(running.readinessUrl).toOption.get)).map(_.status)),
      ).foldZIO(
        error => note(s"${error.getClass.getSimpleName}: ${error.getMessage}").as(false),
        status => note(status.code.toString).as(status.isSuccess),
      )
      result <- (ready <*> running.isAlive.map(!_))
        .repeat(Schedule.spaced(2.seconds) *> Schedule.recurUntil[(Boolean, Boolean)]((r, stopped) => r || stopped))
        .timeout(StartTimeout)
        .timed
      _ <- result match
        case (took, Some((true, _))) =>
          Console.printLine(f"[e2e] ${running.name} ready after ${took.toMillis / 1000.0}%.1f s").ignore *>
            slowStartReport(running, polls).when(took > SlowStart).unit
        case _ => running.output.flatMap(out => ZIO.fail(RuntimeException(s"did not become ready:\n${out.takeRight(4000)}")))
    yield ()

  /** The asks of readiness and the service's own log without its per-request lines, each cut to a screen's width: the log
    * is deleted with the scope. */
  private def slowStartReport(running: Running, polls: Ref[Chunk[String]]): UIO[Unit] =
    (for
      asked <- polls.get
      out <- running.output
      lines = out.linesIterator.filterNot(line => line.contains("send-http") || line.contains("receive-http")).map(_.take(400)).toList
      _ <- Console.printLine(
        s"[e2e] ${running.name} was slow to start. Readiness asked:\n${asked.mkString("\n")}\n[e2e] its log (${lines.size} lines):\n${lines.take(200).mkString("\n")}",
      )
    yield ()).ignore
