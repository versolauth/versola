package versola.oauth.logout

import versola.oauth.client.model.ClientId
import versola.util.UnitSpecBase
import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.test.*

object BackChannelOutboxSpec extends UnitSpecBase:

  private val uriA = URL.decode("https://rp-a.example/backchannel").toOption.get
  private val uriB = URL.decode("https://rp-b.example/backchannel").toOption.get

  private def delivery(uri: URL) = BackChannelOutbox.Delivery(
    audience = NonEmptyChunk(ClientId("client-a")),
    uri = uri,
    subject = "user-1",
    customClaims = Json.Obj(),
  )

  /** No delay between attempts: what is under test is that a rejected delivery is attempted
    * again, not how long the outbox waits before it does.
    */
  private val immediateRetry = Schedule.recurs(1)

  /** A single worker, so that the order deliveries are attempted in is the order they were
    * submitted in and can be asserted on. How many run at once is a capacity bound rather
    * than something any of these tests turn on.
    */
  private def makeOutbox(dispatcher: BackChannelDispatcher) =
    BackChannelOutbox.make(dispatcher, workers = 1, capacity = 8, retry = immediateRetry)

  def spec = suite("BackChannelOutbox")(
    test("attempts every delivery it accepted by the time a drain completes") {
      val dispatcher = stub[BackChannelDispatcher]
      ZIO.scoped:
        for
          _ <- dispatcher.dispatch.succeedsWith(())
          outbox <- makeOutbox(dispatcher)
          _ <- ZIO.foreachDiscard(List(uriA, uriB, uriA))(uri => outbox.submit(delivery(uri)))
          _ <- outbox.awaitDrained
        yield assertTrue(dispatcher.dispatch.calls.map(_._2) == List(uriA, uriB, uriA))
    },
    test("returns from submit while the RP is still being waited on, and drains only once it is done") {
      val dispatcher = stub[BackChannelDispatcher]
      ZIO.scoped:
        for
          released <- Promise.make[Nothing, Unit]
          order <- Ref.make(List.empty[String])
          _ <- dispatcher.dispatch.returnsZIO: (_, _, _, _) =>
            released.await *> order.update(_ :+ "delivered")
          outbox <- makeOutbox(dispatcher)
          // The whole point of the outbox: this returns even though the RP has not answered,
          // so nothing about a logout response depends on the RP answering.
          _ <- outbox.submit(delivery(uriA))
          drain <- (outbox.awaitDrained *> order.update(_ :+ "drained")).fork
          _ <- released.succeed(())
          _ <- drain.join
          result <- order.get
        yield
          // Only the RP being unblocked can let either of these run, so a drain that did not
          // wait for the delivery would have recorded itself first.
          assertTrue(result == List("delivered", "drained"))
    },
    test("attempts a rejected delivery again") {
      val dispatcher = stub[BackChannelDispatcher]
      ZIO.scoped:
        for
          _ <- dispatcher.dispatch.returnsZIOOnCall:
            case 1 => ZIO.fail(RuntimeException("the RP does not know this client yet"))
            case _ => ZIO.succeed(())
          outbox <- makeOutbox(dispatcher)
          _ <- outbox.submit(delivery(uriA))
          _ <- outbox.awaitDrained
        yield assertTrue(dispatcher.dispatch.calls.size == 2)
    },
    test("goes on to the next delivery after one it had to give up on") {
      val dispatcher = stub[BackChannelDispatcher]
      ZIO.scoped:
        for
          _ <- dispatcher.dispatch.returnsZIO: (_, uri, _, _) =>
            ZIO.fail(RuntimeException("connection refused")).when(uri == uriA).unit
          outbox <- makeOutbox(dispatcher)
          _ <- outbox.submit(delivery(uriA))
          _ <- outbox.submit(delivery(uriB))
          _ <- outbox.awaitDrained
        yield
          // A dead RP must not cost the one behind it its event, and must not leave the
          // drain waiting: the failure is logged and the delivery given up on.
          assertTrue(dispatcher.dispatch.calls.map(_._2) == List(uriA, uriA, uriB))
    },
    test("drops the deliveries it has no room for rather than making the caller wait") {
      val dispatcher = stub[BackChannelDispatcher]
      ZIO.scoped:
        for
          released <- Promise.make[Nothing, Unit]
          _ <- dispatcher.dispatch.returnsZIO((_, _, _, _) => released.await)
          // One worker and one queue slot hold two deliveries between them; the rest have
          // nowhere to go while the only RP is unanswering.
          outbox <- BackChannelOutbox.make(dispatcher, workers = 1, capacity = 1, retry = immediateRetry)
          // Every one of these returns, which is the property under test: a saturated outbox
          // must not turn into backpressure on the logout that produced the events.
          _ <- ZIO.foreachDiscard(List.fill(5)(uriA))(uri => outbox.submit(delivery(uri)))
          _ <- released.succeed(())
          _ <- outbox.awaitDrained
        yield assertTrue(dispatcher.dispatch.calls.nonEmpty, dispatcher.dispatch.calls.size < 5)
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)
