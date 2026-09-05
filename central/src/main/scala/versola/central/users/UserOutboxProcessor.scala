package versola.central.users

import versola.central.CentralConfig
import versola.central.CentralConfig.UserOutboxConfig
import zio.{Duration, Fiber, Ref, Schedule, Scope, Semaphore, Task, UIO, URIO, ZIO, ZLayer}

/** Background service that polls `user_outbox`, dispatches events via [[AuthClient]],
  * deletes successful rows, and reschedules failures with exponential back-off.
  *
  * Safe to run on multiple instances: rows are atomically leased by [[UserRepository.claimDueEvents]]
  * via `SELECT ... FOR UPDATE SKIP LOCKED`, so each event is dispatched by exactly one instance.
  * If an instance crashes mid-dispatch the lease expires and another instance picks the row up.
  */
trait UserOutboxProcessor:
  def start(): URIO[Scope, Unit]
  def stop(): UIO[Unit]
  def flush(): Task[Unit]

object UserOutboxProcessor:
  val live: ZLayer[CentralConfig & UserRepository & AuthClient & Scope, Nothing, UserOutboxProcessor] =
    ZLayer:
      ZIO.acquireRelease(
        acquire =
          for
            config <- ZIO.service[CentralConfig]
            repo <- ZIO.service[UserRepository]
            client <- ZIO.service[AuthClient]
            fiberRef <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
            processSemaphore <- Semaphore.make(1)
            processor: UserOutboxProcessor = Live(config.userOutbox, repo, client, fiberRef, processSemaphore)
            _ <- processor.start()
          yield processor,
      )(_.stop())

  private[users] class Live(
      config: UserOutboxConfig,
      repo: UserRepository,
      client: AuthClient,
      fiberRef: Ref[Option[Fiber.Runtime[Nothing, Unit]]],
      processSemaphore: Semaphore,
  ) extends UserOutboxProcessor:

    private val flushRetrySchedule = Schedule.exponential(Duration.fromMillis(100)) && Schedule.recurs(3)

    override def start(): URIO[Scope, Unit] =
      (ZIO.logInfo("Starting OutboxProcessor...")
        .delay(Duration.fromSeconds(20)) *>
          runOnce
            .repeat(Schedule.spaced(config.pollInterval))
            .unit)
        .forkScoped
        .flatMap(f => fiberRef.set(Some(f)))

    override def stop(): UIO[Unit] =
      for
        _ <- ZIO.logInfo("Stopping OutboxProcessor...")
        fiber <- fiberRef.get
        _ <- ZIO.foreachDiscard(fiber)(_.interrupt)
        _ <- ZIO.logInfo("OutboxProcessor stopped")
      yield ()

    override def flush(): Task[Unit] =
      processSemaphore.withPermit:
        def drain: Task[Unit] =
          processFlushOnce.flatMap:
            case (true, failed) if failed.nonEmpty =>
              ZIO.fail(new RuntimeException(s"Outbox flush failed for event(s): ${failed.mkString(", ")}"))
            case (true, _) => drain
            case (false, _) => ZIO.unit
        drain

    private def runOnce: UIO[Boolean] = processSemaphore.withPermit(processOnce)

    private[users] def processOnce: UIO[Boolean] =
      repo.claimDueEvents(config.batchSize, config.lease)
        .flatMap(events => ZIO.foreachDiscard(events)(handle(_, dispatch)).as(events.nonEmpty))
        .catchAllCause(c => ZIO.logErrorCause("user_outbox poll failed", c).as(false))

    private def processFlushOnce: Task[(Boolean, Vector[java.util.UUID])] =
      repo.claimDueEvents(config.batchSize, config.lease).flatMap: events =>
        ZIO.foreach(events)(record => handle(record, dispatchWithRetry)).map: results =>
          (events.nonEmpty, events.zip(results).collect { case (record, false) => record.id })

    private def handle(
        record: OutboxRecord,
        send: OutboxEvent => Task[Unit],
    ): Task[Boolean] =
      send(record.event)
        .foldCauseZIO(
          cause =>
            val nextAttempt = record.attempts + 1
            val recover =
              if nextAttempt >= config.maxAttempts then
                val errorMsg = cause.squash.getMessage
                ZIO.logErrorCause(s"Outbox ${record.id} exceeded max attempts (${config.maxAttempts}). Moving to dead letter. Event: ${record.event}", cause) *>
                  repo.moveToDeadLetter(record.id, errorMsg).catchAllCause(c => ZIO.logErrorCause("moveToDeadLetter failed", c))
              else
                val delay = backoff(nextAttempt)
                ZIO.logWarningCause(s"Outbox ${record.id} dispatch failed (attempt $nextAttempt). Event: ${record.event}", cause) *>
                  repo.rescheduleEvent(record.id, delay).catchAllCause(c => ZIO.logErrorCause("reschedule failed", c))
            recover.as(false)
          ,
          _ => repo.deleteEvent(record.id).foldCauseZIO(
            c => ZIO.logErrorCause("delete failed", c).as(false),
            _ => ZIO.succeed(true),
          ),
        )

    private def dispatch(event: OutboxEvent): ZIO[Any, Throwable, Unit] =
      event match
        case e: OutboxEvent.UpsertUser => client.upsertUser(e.userId, e.version, e.email, e.phone, e.login)
        case e: OutboxEvent.UpdateUserRoles => client.updateUserRoles(e.userId, e.tenantId, e.add, e.remove)
        case e: OutboxEvent.DeleteUser => client.deleteUser(e.userId)

    private def dispatchWithRetry(event: OutboxEvent): Task[Unit] =
      dispatch(event).retry(flushRetrySchedule)

    private def backoff(attempts: Int): Duration =
      val seconds = math.min(math.pow(2.0, attempts).toLong, config.maxBackoff.toSeconds)
      Duration.fromSeconds(seconds)
