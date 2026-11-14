package versola.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.PostgresOAuthApp
import versola.util.SecureRandom
import versola.util.audit.*
import versola.util.http.{NoopTracing, VersolaApp}
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

/** What `auth` has stored when the audit writer's scope closes, with the layers composed in the order `PostgresOAuthApp` composes them:
  * the transactor, the repository on it, the settings, then `AuditEvents`. The writer's rest between batches is longer than any of the
  * cases, so what is stored at the end of a case is what the close wrote. Run with the live clock: the writer and the close sleep. */
object PostgresAuditShutdownSpec extends PostgresSpec:

  private val settings = AuditEvents.Settings(retention = 7.days, overflow = AuditEvents.Overflow.block, blockTimeout = 2.seconds)

  private val draft = AuditDraft(
    kind = AuthAuditKinds.LoginStep,
    outcome = AuditOutcome.failure,
    reason = Some("shutdown_spec"),
    actor = None,
    userId = None,
    target = None,
    details = AuditDetails.empty,
  )

  /** A repository whose `insertBatch` takes `delay` before it writes, as a slow database does. */
  private final class Delayed(inner: AuditEventRepository, delay: Duration) extends AuditEventRepository:
    export inner.{insertBatch as _, *}

    override def insertBatch(events: List[AuditEvent]): Task[Unit] =
      ZIO.sleep(delay) *> inner.insertBatch(events)

  private def stored(node: String) =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect(sql"SELECT COUNT(*) FROM auth_audit_event WHERE node_id = $node".query[Long].run().head)

  /** Records `n` events through the layers of the app, closes their scope and says how many rows of this node are in the table. */
  private def recordedThenClosed(node: String, n: Int, sizing: AuditEvents.Sizing, delay: Option[Duration]) =
    val repository: ZLayer[TransactorZIO, Nothing, AuditEventRepository] =
      ZLayer.fromFunction: (xa: TransactorZIO) =>
        val real: AuditEventRepository = PostgresAuditEventRepository(xa, PostgresAuditEventRepository.partitions)
        delay.fold(real)(d => Delayed(real, d))
    val layers = repository ++ ZLayer.succeed(settings) ++ ZLayer.succeed[AuditTenants](AuditTenants.none) ++ NoopTracing.layer ++ SecureRandom.live
    ZIO.scoped:
      (layers >>> AuditEvents.liveWith(sizing)(AuthAudit.source, node, AuthAudit.catalog, PostgresAuditEventRepository.Table)).build.flatMap: env =>
        ZIO.foreachDiscard(1 to n)(_ => env.get[AuditEvents].record(draft).catchAll(_ => ZIO.dieMessage("the queue refused an event")))
    *> stored(node)

  override def spec =
    suite("PostgresAuditShutdownSpec")(
      test("closing the scope writes every event recorded, more of them than a batch holds, while the writer rests") {
        val node = s"shutdown-spec-${java.util.UUID.randomUUID()}"
        for count <- recordedThenClosed(node, n = 350, AuditEvents.Sizing(batchSize = 100, flushInterval = 1.hour), delay = None)
        yield assertTrue(count == 350L)
      },
      test("closing the scope while the repository is slow inside insertBatch still stores every event, once") {
        val node = s"shutdown-spec-${java.util.UUID.randomUUID()}"
        for count <- recordedThenClosed(node, n = 350, AuditEvents.Sizing(batchSize = 100, flushInterval = 50.millis), delay = Some(400.millis))
        yield assertTrue(count == 350L)
      },
      test("the drain of the writer and its margin fit inside the time the app allows its shutdown") {
        assertTrue(
          AuditEvents.DrainTimeout + 2.seconds <= VersolaApp.GracefulShutdownTimeout,
          PostgresOAuthApp.gracefulShutdownTimeout == VersolaApp.GracefulShutdownTimeout,
        )
      },
    ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
