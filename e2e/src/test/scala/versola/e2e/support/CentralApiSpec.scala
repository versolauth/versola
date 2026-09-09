package versola.e2e.support

import zio.*
import zio.http.Client
import zio.test.{TestAspect, TestAspectAtLeastR, TestEnvironment, ZIOSpec}

/** Base class for the isolated central admin API specs.
  *
  * Unlike [[E2ESpec]] these tests never sign anybody in, so they deliberately skip the
  * `Flows` bootstrap: no clients, users, challenge settings or configuration syncs are set
  * up. What each test needs, it creates and cleans up itself, which is what makes a failure
  * here point at central's API rather than at somebody else's fixture.
  */
abstract class CentralApiSpec extends ZIOSpec[CentralApi]:
  override val bootstrap: ZLayer[Any, Any, CentralApi] =
    (E2EConfig.live ++ Client.default) >>> CentralApi.live

  /** Several admin writes answer `202` and settle through the user outbox, so the assertions
    * have to sleep and retry against real time. Under the default test clock those schedules
    * never advance and the test hangs instead of failing.
    */
  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock)

  /** Reads until the answer reflects the write the test just made.
    *
    * Central serves its configuration reads from caches that a Postgres notification refreshes
    * once the write has committed, so a read issued immediately afterwards can still show the
    * previous state. Polling is what separates "the API lost my write" from "the API had not
    * caught up yet"; on a timeout the last answer is returned rather than an error, so the
    * failure report shows the stale value instead of a bare timeout.
    */
  def eventually[A](read: Task[A])(condition: A => Boolean): Task[A] =
    read
      .repeat(Schedule.spaced(50.millis) *> Schedule.recurUntil(condition))
      .timeout(10.seconds)
      .someOrElseZIO(read)

  val api: URIO[CentralApi, CentralApi] = ZIO.service[CentralApi]
