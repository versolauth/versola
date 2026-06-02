package versola.central.users

import versola.central.CentralConfig
import versola.central.CentralConfig.UserOutboxConfig
import zio.{Duration, Fiber, Ref, Schedule, Scope, UIO, URIO, ZIO, ZLayer}

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
            processor: UserOutboxProcessor = Live(config.userOutbox, repo, client, fiberRef)
            _ <- processor.start()
          yield processor,
      )(_.stop())

  private[users] class Live(
      config: UserOutboxConfig,
      repo: UserRepository,
      client: AuthClient,
      fiberRef: Ref[Option[Fiber.Runtime[Nothing, Unit]]],
  ) extends UserOutboxProcessor:

    override def start(): URIO[Scope, Unit] =
      ZIO.logInfo("Starting OutboxProcessor...") *>
        processOnce
          .repeat(Schedule.spaced(config.pollInterval))
          .unit
          .forkScoped
          .flatMap(f => fiberRef.set(Some(f)))

    override def stop(): UIO[Unit] =
      for
        _ <- ZIO.logInfo("Stopping OutboxProcessor...")
        fiber <- fiberRef.get
        _ <- ZIO.foreachDiscard(fiber)(_.interrupt)
        _ <- ZIO.logInfo("OutboxProcessor stopped")
      yield ()

    private[users] def processOnce: ZIO[Any, Nothing, Unit] =
      repo.claimDueEvents(config.batchSize, config.lease)
        .flatMap(ZIO.foreachDiscard(_)(handle))
        .catchAllCause(c => ZIO.logErrorCause("user_outbox poll failed", c))

    private def handle(record: OutboxRecord): ZIO[Any, Nothing, Unit] =
      dispatch(record.event)
        .foldCauseZIO(
          cause =>
            val delay = backoff(record.attempts + 1)
            ZIO.logWarningCause(s"Outbox ${record.id} dispatch failed (attempt ${record.attempts + 1}). Event: ${record.event}", cause) *>
              repo.rescheduleEvent(record.id, delay).catchAllCause(c => ZIO.logErrorCause("reschedule failed", c))
          ,
          _ => repo.deleteEvent(record.id).catchAllCause(c => ZIO.logErrorCause("delete failed", c)),
        )

    private def dispatch(event: OutboxEvent): ZIO[Any, Throwable, Unit] =
      event match
        case e: OutboxEvent.UpsertUser => client.upsertUser(e.userId, e.version, e.email, e.phone, e.login)

    private def backoff(attempts: Int): Duration =
      val seconds = math.min(math.pow(2.0, attempts).toLong, config.maxBackoff.toSeconds)
      Duration.fromSeconds(seconds)
