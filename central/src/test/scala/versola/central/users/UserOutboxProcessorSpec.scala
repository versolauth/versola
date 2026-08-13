package versola.central.users

import org.scalamock.stubs.ZIOStubs
import versola.central.CentralConfig.UserOutboxConfig
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.json.ast.Json
import zio.test.*
import zio.{Cause, Duration, Exit, Fiber, IO, Ref, Semaphore, Task, ZIO}

import java.util.UUID

object UserOutboxProcessorSpec extends ZIOSpecDefault, ZIOStubs:

  private val userId  = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val eventId = UUID.fromString("00000000-0000-0000-0000-000000000099")

  private val config = UserOutboxConfig(
    pollInterval = Duration.fromSeconds(1),
    batchSize = 16,
    lease = Duration.fromSeconds(60),
    maxBackoff = Duration.fromSeconds(300),
    maxAttempts = 10,
  )

  private val record = OutboxRecord(
    id = eventId,
    userId = userId,
    event = OutboxEvent.UpsertUser(userId, eventId, Some(Email("a@b.c")), None, None),
    attempts = 0,
  )

  private def runFlush(processor: UserOutboxProcessor): Task[Unit] =
    for
      fiber <- processor.flush().fork
      _ <- ZIO.foreachDiscard(1 to 8)(_ => TestClock.adjust(Duration.fromSeconds(1)))
      _ <- fiber.join
    yield ()

  private def runFlushExit(processor: UserOutboxProcessor): Task[Exit[Throwable, Unit]] =
    for
      fiber <- processor.flush().exit.fork
      _ <- ZIO.foreachDiscard(1 to 8)(_ => TestClock.adjust(Duration.fromSeconds(1)))
      exit <- fiber.join
    yield exit

  def spec = suite("UserOutboxProcessor")(
    test("logs a warning and reschedules when dispatch fails") {
      val repo = stub[UserRepository]
      val client = stub[AuthClient]

      for
        _ <- repo.claimDueEvents.succeedsWith(Vector(record))
        _ <- client.upsertUser.failsWith(new RuntimeException("Auth call failed: 500 boom"))
        _ <- repo.rescheduleEvent.succeedsWith(())

        fiberRef <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
        semaphore <- Semaphore.make(1)
        processor = UserOutboxProcessor.Live(config, repo, client, fiberRef, semaphore)
        _ <- processor.processOnce
        logs <- ZTestLogger.logOutput
      yield
        val warning = logs.find(e => e.logLevel == zio.LogLevel.Warning && e.message().contains(eventId.toString))
        assertTrue(
          repo.rescheduleEvent.calls.length == 1,
          repo.rescheduleEvent.calls.head._1 == eventId,
          repo.deleteEvent.calls.isEmpty,
          repo.moveToDeadLetter.calls.isEmpty,
          warning.isDefined,
          warning.exists(_.cause.failures.exists { case ex: Throwable => ex.getMessage.contains("500 boom") }),
        )
    },
    test("deletes the event on successful dispatch and emits no warning") {
      val repo = stub[UserRepository]
      val client = stub[AuthClient]

      for
        _ <- repo.claimDueEvents.succeedsWith(Vector(record))
        _ <- client.upsertUser.succeedsWith(())
        _ <- repo.deleteEvent.succeedsWith(())

        fiberRef <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
        semaphore <- Semaphore.make(1)
        processor = UserOutboxProcessor.Live(config, repo, client, fiberRef, semaphore)
        _ <- processor.processOnce
        logs <- ZTestLogger.logOutput
      yield assertTrue(
        repo.deleteEvent.calls == List(eventId),
        repo.rescheduleEvent.calls.isEmpty,
        repo.moveToDeadLetter.calls.isEmpty,
        !logs.exists(_.logLevel == zio.LogLevel.Warning),
      )
    },
    test("flush drains all due batches before returning") {
      val repo = stub[UserRepository]
      val client = stub[AuthClient]

      for
        _ <- repo.claimDueEvents.returnsZIOOnCall:
          case 1 => ZIO.succeed(Vector(record))
          case 2 => ZIO.succeed(Vector.empty)
        _ <- client.upsertUser.succeedsWith(())
        _ <- repo.deleteEvent.succeedsWith(())
        fiberRef <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
        semaphore <- Semaphore.make(1)
        processor = UserOutboxProcessor.Live(config, repo, client, fiberRef, semaphore)
        _ <- runFlush(processor)
      yield assertTrue(
        repo.claimDueEvents.calls.length == 2,
        repo.deleteEvent.calls == List(eventId),
      )
    },
      test("flush retries a transient dispatch failure") {
        val repo = stub[UserRepository]
        val client = stub[AuthClient]

        for
          _ <- repo.claimDueEvents.returnsZIOOnCall:
            case 1 => ZIO.succeed(Vector(record))
            case 2 => ZIO.succeed(Vector.empty)
          _ <- client.upsertUser.returnsZIOOnCall:
            case 1 => ZIO.fail(new RuntimeException("temporary failure"))
            case 2 => ZIO.succeed(())
          _ <- repo.deleteEvent.succeedsWith(())

          fiberRef <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
          semaphore <- Semaphore.make(1)
          processor = UserOutboxProcessor.Live(config, repo, client, fiberRef, semaphore)
          _ <- runFlush(processor)
        yield assertTrue(
          client.upsertUser.calls.length == 2,
          repo.deleteEvent.calls == List(eventId),
        )
      },
      test("flush fails when dispatch cannot be completed") {
        val repo = stub[UserRepository]
        val client = stub[AuthClient]

        for
          _ <- repo.claimDueEvents.succeedsWith(Vector(record))
          _ <- client.upsertUser.failsWith(new RuntimeException("Auth unavailable"))
          _ <- repo.rescheduleEvent.succeedsWith(())

          fiberRef <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
          semaphore <- Semaphore.make(1)
          processor = UserOutboxProcessor.Live(config, repo, client, fiberRef, semaphore)
          exit <- runFlushExit(processor)
        yield assertTrue(
          exit.isFailure,
          client.upsertUser.calls.length > 1,
          repo.rescheduleEvent.calls == List((eventId, Duration.fromSeconds(2))),
          repo.deleteEvent.calls.isEmpty,
        )
      },
    test("moves event to dead letter when max attempts exceeded") {
      val failedRecord = record.copy(attempts = config.maxAttempts - 1)
      val repo = stub[UserRepository]
      val client = stub[AuthClient]

      for
        _ <- repo.claimDueEvents.succeedsWith(Vector(failedRecord))
        _ <- client.upsertUser.failsWith(new RuntimeException("Permanent failure"))
        _ <- repo.moveToDeadLetter.succeedsWith(())

        fiberRef <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
        semaphore <- Semaphore.make(1)
        processor = UserOutboxProcessor.Live(config, repo, client, fiberRef, semaphore)
        _ <- processor.processOnce
        logs <- ZTestLogger.logOutput
      yield
        val error = logs.find(e => e.logLevel == zio.LogLevel.Error && e.message().contains("exceeded max attempts"))
        assertTrue(
          repo.moveToDeadLetter.calls.length == 1,
          repo.moveToDeadLetter.calls.head._1 == eventId,
          repo.moveToDeadLetter.calls.head._2 == "Permanent failure",
          repo.rescheduleEvent.calls.isEmpty,
          repo.deleteEvent.calls.isEmpty,
          error.isDefined,
        )
    },
  )
