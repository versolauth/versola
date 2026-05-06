package versola.central.users

import versola.central.CentralConfig.UserOutboxConfig
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{Email, Patch, Phone}
import zio.json.ast.Json
import zio.test.*
import zio.{Cause, Duration, Fiber, IO, Ref, Task, ZIO}

import java.util.UUID

object UserOutboxProcessorSpec extends ZIOSpecDefault:

  private val userId  = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val eventId = UUID.fromString("00000000-0000-0000-0000-000000000099")

  private val config = UserOutboxConfig(
    pollInterval = Duration.fromSeconds(1),
    batchSize = 16,
    lease = Duration.fromSeconds(60),
    maxBackoff = Duration.fromSeconds(300),
  )

  private val record = OutboxRecord(
    id = eventId,
    event = OutboxEvent.CreateUser(userId, Some(Email("a@b.c")), None, None, Json.Obj()),
    attempts = 0,
  )

  private class StubRepo(
      claim: Ref[Vector[OutboxRecord]],
      val rescheduled: Ref[Vector[UUID]],
      val deleted: Ref[Vector[UUID]],
  ) extends UserRepository:
    override def claimDueEvents(limit: Int, lease: Duration): Task[Vector[OutboxRecord]] =
      claim.getAndSet(Vector.empty)
    override def deleteEvent(id: UUID): Task[Unit] = deleted.update(_ :+ id)
    override def rescheduleEvent(id: UUID, delay: Duration): Task[Unit] = rescheduled.update(_ :+ id)
    override def findById(id: UserId) = ZIO.none
    override def findByEmail(email: Email) = ZIO.none
    override def findByPhone(phone: Phone) = ZIO.none
    override def findByLogin(login: Login) = ZIO.none
    override def create(id: UserId, email: Option[Email], phone: Option[Phone], login: Option[Login], claims: Json.Obj)
        : IO[UserConflict | Throwable, Unit] = ZIO.unit
    override def patch(
        id: UserId,
        email: Option[Patch[Email]],
        phone: Option[Patch[Phone]],
        login: Option[Patch[Login]],
        claims: Option[Json.Obj],
    ): Task[Unit] = ZIO.unit
    override def insertRole(userId: UserId, tenantId: TenantId, roleId: RoleId): Task[Unit] = ZIO.unit
    override def deleteRole(userId: UserId, tenantId: TenantId, roleId: RoleId): Task[Unit] = ZIO.unit

  private class FailingAuthClient(error: Throwable) extends AuthClient:
    override def createUser(id: UserId, email: Option[Email], phone: Option[Phone], login: Option[Login], claims: Json.Obj): Task[Unit] =
      ZIO.fail(error)
    override def patchUser(
        id: UserId,
        email: Option[Patch[Email]],
        phone: Option[Patch[Phone]],
        login: Option[Patch[Login]],
        claims: Option[Json.Obj],
    ): Task[Unit] = ZIO.fail(error)
    override def assignRole(userId: UserId, tenantId: TenantId, roleId: RoleId): Task[Unit] = ZIO.fail(error)
    override def removeRole(userId: UserId, tenantId: TenantId, roleId: RoleId): Task[Unit] = ZIO.fail(error)
    override def getUserClaims(id: UserId): Task[Option[Json.Obj]] = ZIO.fail(error)
    override def getUserRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]] = ZIO.fail(error)

  private class OkAuthClient extends AuthClient:
    override def createUser(id: UserId, email: Option[Email], phone: Option[Phone], login: Option[Login], claims: Json.Obj): Task[Unit] =
      ZIO.unit
    override def patchUser(
        id: UserId,
        email: Option[Patch[Email]],
        phone: Option[Patch[Phone]],
        login: Option[Patch[Login]],
        claims: Option[Json.Obj],
    ): Task[Unit] = ZIO.unit
    override def assignRole(userId: UserId, tenantId: TenantId, roleId: RoleId): Task[Unit] = ZIO.unit
    override def removeRole(userId: UserId, tenantId: TenantId, roleId: RoleId): Task[Unit] = ZIO.unit
    override def getUserClaims(id: UserId): Task[Option[Json.Obj]] = ZIO.none
    override def getUserRoles(id: UserId, tenantId: TenantId): Task[List[RoleId]] = ZIO.succeed(Nil)

  def spec = suite("UserOutboxProcessor")(
    test("logs a warning and reschedules when dispatch fails") {
      for
        claim       <- Ref.make(Vector(record))
        rescheduled <- Ref.make(Vector.empty[UUID])
        deleted     <- Ref.make(Vector.empty[UUID])
        repo        = StubRepo(claim, rescheduled, deleted)
        client      = FailingAuthClient(new RuntimeException("Auth call failed: 500 boom"))
        fiberRef    <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
        processor   = UserOutboxProcessor.Live(config, repo, client, fiberRef)
        _           <- processor.processOnce
        logs        <- ZTestLogger.logOutput
        rescheduledIds <- rescheduled.get
        deletedIds  <- deleted.get
      yield
        val warning = logs.find(e => e.logLevel == zio.LogLevel.Warning && e.message().contains(eventId.toString))
        assertTrue(
          rescheduledIds == Vector(eventId),
          deletedIds.isEmpty,
          warning.isDefined,
          warning.exists(_.cause.failures.exists { case ex: Throwable => ex.getMessage.contains("500 boom") }),
        )
    },
    test("deletes the event on successful dispatch and emits no warning") {
      for
        claim       <- Ref.make(Vector(record))
        rescheduled <- Ref.make(Vector.empty[UUID])
        deleted     <- Ref.make(Vector.empty[UUID])
        repo        = StubRepo(claim, rescheduled, deleted)
        fiberRef    <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
        processor   = UserOutboxProcessor.Live(config, repo, OkAuthClient(), fiberRef)
        _           <- processor.processOnce
        logs        <- ZTestLogger.logOutput
        rescheduledIds <- rescheduled.get
        deletedIds  <- deleted.get
      yield assertTrue(
        deletedIds == Vector(eventId),
        rescheduledIds.isEmpty,
        !logs.exists(_.logLevel == zio.LogLevel.Warning),
      )
    },
  )
