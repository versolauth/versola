package versola.user

import versola.util.CoreConfig
import versola.util.CoreConfig.UserRegistrationOutboxConfig
import zio.{Duration, Fiber, Ref, Schedule, Scope, Task, UIO, URIO, ZIO, ZLayer}

/** Background service that polls `user_registration_outbox`, reports each registration to
  * central, deletes successful rows, and reschedules failures with exponential back-off.
  *
  * Safe to run on multiple instances: rows are atomically leased by
  * [[UserRegistrationOutboxRepository.claimDueEvents]] via `SELECT ... FOR UPDATE SKIP LOCKED`,
  * so each event is dispatched by exactly one instance. If an instance crashes mid-dispatch the
  * lease expires and another instance picks the row up.
  */
trait UserRegistrationOutboxProcessor:
  def start(): URIO[Scope, Unit]
  def stop(): UIO[Unit]
  def flush(): Task[Unit]

object UserRegistrationOutboxProcessor:
  val live: ZLayer[CoreConfig & UserRegistrationOutboxRepository & UserRegistrationSyncClient & Scope, Nothing, UserRegistrationOutboxProcessor] =
    ZLayer:
      ZIO.acquireRelease(
        acquire =
          for
            config <- ZIO.service[CoreConfig]
            repo <- ZIO.service[UserRegistrationOutboxRepository]
            client <- ZIO.service[UserRegistrationSyncClient]
            fiberRef <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
            processor: UserRegistrationOutboxProcessor =
              Live(config.userRegistrationOutboxOrDefault, repo, client, fiberRef)
            _ <- processor.start()
          yield processor,
      )(_.stop())

  private[user] class Live(
      config: UserRegistrationOutboxConfig,
      repo: UserRegistrationOutboxRepository,
      client: UserRegistrationSyncClient,
      fiberRef: Ref[Option[Fiber.Runtime[Nothing, Unit]]],
  ) extends UserRegistrationOutboxProcessor:

    override def start(): URIO[Scope, Unit] =
      (ZIO.logInfo("Starting UserRegistrationOutboxProcessor...")
        .delay(Duration.fromSeconds(20)) *>
          processOnce
            .repeat(Schedule.spaced(config.pollInterval))
            .unit)
        .forkScoped
        .flatMap(f => fiberRef.set(Some(f)))

    override def stop(): UIO[Unit] =
      for
        _ <- ZIO.logInfo("Stopping UserRegistrationOutboxProcessor...")
        fiber <- fiberRef.get
        _ <- ZIO.foreachDiscard(fiber)(_.interrupt)
        _ <- ZIO.logInfo("UserRegistrationOutboxProcessor stopped")
      yield ()

    override def flush(): Task[Unit] = processOnce

    private[user] def processOnce: ZIO[Any, Nothing, Unit] =
      repo.claimDueEvents(config.batchSize, config.lease)
        .flatMap(ZIO.foreachDiscard(_)(handle))
        .catchAllCause(c => ZIO.logErrorCause("user_registration_outbox poll failed", c))

    private def handle(record: UserRegistrationOutboxRecord): ZIO[Any, Nothing, Unit] =
      client.reportRegistration(record.event)
        .foldCauseZIO(
          cause =>
            val nextAttempt = record.attempts + 1
            if nextAttempt >= config.maxAttempts then
              val errorMsg = cause.squash.getMessage
              ZIO.logErrorCause(s"Registration outbox ${record.id} exceeded max attempts (${config.maxAttempts}). Moving to dead letter.", cause) *>
                repo.moveToDeadLetter(record.id, errorMsg).catchAllCause(c => ZIO.logErrorCause("moveToDeadLetter failed", c))
            else
              val delay = backoff(nextAttempt)
              ZIO.logWarningCause(s"Registration outbox ${record.id} dispatch failed (attempt $nextAttempt).", cause) *>
                repo.rescheduleEvent(record.id, delay).catchAllCause(c => ZIO.logErrorCause("reschedule failed", c))
          ,
          _ => repo.deleteEvent(record.id).catchAllCause(c => ZIO.logErrorCause("delete failed", c)),
        )

    private def backoff(attempts: Int): Duration =
      val seconds = math.min(math.pow(2.0, attempts).toLong, config.maxBackoff.toSeconds)
      Duration.fromSeconds(seconds)
