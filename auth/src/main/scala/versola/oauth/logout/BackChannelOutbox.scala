package versola.oauth.logout

import versola.oauth.client.model.ClientId
import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.stm.TRef

/** Takes security-event deliveries off the fiber that produced them.
  *
  * A logout fans out to every RP that took part in the session, and no RP may delay or fail
  * the user's own logout response (OIDC Back-Channel Logout §2.4). Forking each delivery
  * meets that, but leaves nobody holding the work: nothing bounds how many deliveries run
  * at once, a shutdown drops whatever is in flight, an event the RP rejected is lost, and a
  * caller that has to observe the fan-out — a test above all — can only poll for it.
  */
trait BackChannelOutbox:
  /** Accepts a delivery. Returns once it has been taken on, not once it has been made. */
  def submit(delivery: BackChannelOutbox.Delivery): UIO[Unit]

  /** Completes once every delivery accepted before this call has been attempted, whether it
    * succeeded or was given up on. This is what makes the fan-out observable: a caller that
    * has to see a logout through — a test, or a shutdown finishing its last events — waits
    * here rather than polling. Deliveries accepted after the call may also be waited for.
    */
  def awaitDrained: UIO[Unit]

object BackChannelOutbox:
  case class Delivery(
      audience: NonEmptyChunk[ClientId],
      uri: URL,
      subject: String,
      customClaims: Json.Obj,
  )

  /** How many deliveries are attempted at once. One logout is one delivery per distinct
    * endpoint, so this bound is reached by many logouts overlapping rather than by one.
    */
  private val Workers = 16

  /** How many accepted deliveries may be waiting for a worker. */
  private val Capacity = 1024

  /** An RP that is briefly unreachable, or that does not yet know the client the event names,
    * rejects a delivery that would have succeeded moments later. Both events this carries are
    * idempotent — ending an ended session, revoking a revoked token — so a delivery the RP did
    * process but failed to acknowledge costs a duplicate rather than a wrong outcome.
    */
  private val DeliveryRetry = Schedule.exponential(200.millis) && Schedule.recurs(3)

  /** How long a shutdown gives the deliveries it has already accepted. */
  private val DrainTimeout = 10.seconds

  val live: ZLayer[BackChannelDispatcher & Scope, Nothing, BackChannelOutbox] =
    ZLayer.fromZIO:
      for
        dispatcher <- ZIO.service[BackChannelDispatcher]
        outbox <- make(dispatcher)
        // Registered after the workers were forked, so it runs before they are interrupted:
        // events accepted from a logout that landed during shutdown are still delivered.
        _ <- ZIO.addFinalizer(outbox.awaitDrained.timeout(DrainTimeout))
      yield outbox

  /** Builds an outbox and forks its workers into the current scope.
    *
    * The retry schedule is a parameter so that a test can supply one that does not wait,
    * and so make draining independent of the clock.
    */
  def make(
      dispatcher: BackChannelDispatcher,
      workers: Int = Workers,
      capacity: Int = Capacity,
      retry: Schedule[Any, Any, Any] = DeliveryRetry,
  ): ZIO[Scope, Nothing, BackChannelOutbox] =
    for
      queue <- Queue.dropping[Delivery](capacity)
      outstanding <- TRef.make(0).commit
      outbox = Impl(dispatcher, queue, outstanding, retry)
      _ <- ZIO.replicateZIODiscard(workers)(outbox.work.forkScoped)
    yield outbox

  class Impl(
      dispatcher: BackChannelDispatcher,
      queue: Queue[Delivery],
      outstanding: TRef[Int],
      retry: Schedule[Any, Any, Any],
  ) extends BackChannelOutbox:

    private def settle: UIO[Unit] =
      outstanding.update(_ - 1).commit

    override def submit(delivery: Delivery): UIO[Unit] =
      for
        _ <- outstanding.update(_ + 1).commit
        accepted <- queue.offer(delivery)
        // Dropped rather than made to wait: the queue is only ever full because the RPs are
        // not keeping up, and the user's own logout must not queue behind them.
        _ <- ZIO.unless(accepted):
          settle *> ZIO.logWarning(s"Back-channel outbox is full; dropped the event for '${delivery.uri}'")
      yield ()

    // Retried by the STM runtime when the count changes rather than on a timer, so a drain
    // costs nothing while it waits and observes the last delivery settling immediately.
    override def awaitDrained: UIO[Unit] =
      outstanding.get.retryUntil(_ == 0).unit.commit

    val work: UIO[Nothing] =
      queue.take.flatMap(deliver).forever

    private def deliver(delivery: Delivery): UIO[Unit] =
      dispatcher
        .dispatch(
          audience = delivery.audience,
          uri = delivery.uri,
          subject = delivery.subject,
          customClaims = delivery.customClaims,
        )
        .retry(retry)
        .catchAllCause(cause => ZIO.logWarningCause(s"Back-channel delivery to '${delivery.uri}' failed", cause))
        // Counted down even when the worker is interrupted mid-delivery, so a shutdown
        // cannot leave a drain waiting on a delivery that will never be retried.
        .ensuring(settle)
