package versola.util.http

import zio.*
import zio.http.*
import zio.test.*

import java.net.ServerSocket

/** What a shutdown does first. The service goes not-ready and starts the shared budget when the fiber holding the server open is
  * interrupted, which is before the server stops and waits for its requests in flight; a finalizer of the app's outer scope would
  * run after that wait, with the port already closed.
  */
object ServeUntilShutdownSpec extends ZIOSpecDefault:

  private val serverWaits = 3.seconds

  private def freePort: Task[Int] =
    ZIO.attempt:
      val socket = new ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()

  def spec = suite("serveUntilShutdown")(
    test("is ready while it serves and not ready, with the budget begun, while the server still waits for a request in flight") {
      for
        port <- freePort
        readiness <- ReadinessService.make
        entered <- Promise.make[Nothing, Unit]
        routes = Routes(Method.GET / "slow" -> handler(entered.succeed(()) *> ZIO.sleep(1.hour).as(Response.ok)))
        // The shape of VersolaApp.run: the server is a layer, and the fiber that holds it open is inside its `provide`.
        app <- (Server.install(routes) *> VersolaApp.serveUntilShutdown(readiness))
          .provide(Server.live, ZLayer.succeed(Server.Config.default.port(port).gracefulShutdownTimeout(serverWaits)))
          .fork
        _ <- readiness.isReady.repeatUntil(identity)
        beforeBudget <- ShutdownBudget.allowed(1.hour)
        url = URL.decode(s"http://localhost:$port/slow").toOption.get
        _ <- ZIO.scoped(ZIO.serviceWithZIO[Client](_.batched(Request.get(url)))).provide(Client.default).forkDaemon
        _ <- entered.await
        stopping <- app.interrupt.fork
        _ <- readiness.isReady.repeatUntil(!_)
        stillStopping <- stopping.poll
        // Not ready comes first and the budget begins a moment later, in the same release: wait for it rather than read between them.
        duringBudget <- ShutdownBudget.allowed(1.hour).repeatUntil(_ < 1.hour)
        _ <- stopping.join
      yield assertTrue(
        beforeBudget == 1.hour,              // not begun while it serves
        stillStopping.isEmpty,               // the server is still waiting for the request: not ready came first, not after
        duringBudget <= ShutdownBudget.Total, // begun
      )
    } @@ TestAspect.after(ZIO.succeed(ShutdownBudget.reset())),
  ) @@ ShutdownBudgetExclusive.aspect @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds) @@ TestAspect.sequential
