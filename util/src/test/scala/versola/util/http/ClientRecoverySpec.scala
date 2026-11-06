package versola.util.http

import zio.*
import zio.http.*
import zio.test.*

import java.net.ServerSocket

/** What the outbound pool does after its target refused a connection and then came up: the
  * behaviour [[versola.util.ReloadingCache]]'s startup wait depends on.
  */
object ClientRecoverySpec extends ZIOSpecDefault:

  private val poolSize = 4

  private def freePort: Task[Int] =
    ZIO.attempt:
      val socket = new ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()

  /** Requests to a port nobody listens on, starts a server there, and counts the requests it takes
    * to get a response; `None` if none of the first `poolSize * 3` succeeds.
    */
  private def attemptsToRecover(pool: ConnectionPoolConfig): ZIO[Any, Throwable, Option[Int]] =
    ZIO.scoped:
      for
        port <- freePort
        client <- Observability.pooledClient(pool).build.map(_.get[Client])
        url = URL.decode(s"http://localhost:$port/ok").toOption.get
        get = ZIO.scoped(client.batched(Request.get(url))).either
        _ <- get
        server <- Server.defaultWith(_.port(port)).build.map(_.get[Server])
        _ <- server.install(Routes(Method.GET / "ok" -> handler(Response.text("ok"))))
        results <- ZIO.foreach(1 to poolSize * 3)(_ => get)
      yield results.indexWhere(_.isRight) match
        case -1 => None
        case i => Some(i + 1)

  def spec = suite("client recovery after a refused connection")(
    test("a dynamic pool connects on the first attempt after the target comes up") {
      attemptsToRecover(ConnectionPoolConfig.Dynamic(0, poolSize, 30.seconds)).map(n => assertTrue(n.contains(1)))
    },
    test("a fixed pool keeps failing until its stored failures are used up") {
      attemptsToRecover(ConnectionPoolConfig.Fixed(poolSize)).map(n => assertTrue(n.exists(_ > 1)))
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds) @@ TestAspect.sequential
