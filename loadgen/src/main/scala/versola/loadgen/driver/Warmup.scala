package versola.loadgen.driver

import versola.loadgen.config.TargetsConfig
import versola.loadgen.protocol.DpopKeyPool
import zio.http.{Client, Method, Request}
import zio.{Duration, UIO, ZIO, durationInt}

/** What a driver does before it says it is ready: the calls a process makes for the first time
  * once, unmeasured, so that the first call of a campaign is not the one that pays for them.
  *
  * The first HTTPS request of a JVM initialises JSSE and its provider chain, loads the truststore,
  * resolves the name and opens the pooled connection; the first DPoP proof loads and JITs the
  * signature code. None of that is the system under test, and none of it recurs -- but on a few
  * hundred refreshes the one or two calls that carried it *are* the p99 (a 1-2 s refresh against a
  * 25 ms median, once per driver start, in every campaign of the first fapi2 runs).
  *
  * Best effort and bounded: a call that fails or stalls is logged and the driver goes on. The
  * point is to have warmed what could be warmed, not to gate the campaign on the SUT answering,
  * which is the coordinator's and the first real call's business.
  *
  * Plain GETs that need no credential and change nothing: auth's discovery document and edge's
  * resource root (an unauthenticated request there is refused, which exercises the same path as a
  * refused one costs nothing and writes nothing). A request is made `rounds` times so the JIT sees
  * the path more than once.
  */
object Warmup:
  val rounds: Int = 3
  val callTimeout: Duration = 5.seconds

  def urls(targets: TargetsConfig): List[String] =
    List(
      targets.authUrl.stripSuffix("/") + "/.well-known/openid-configuration",
      targets.edgeUrl.stripSuffix("/") + "/resources/",
    )

  def run(client: Client, targets: TargetsConfig, dpop: Option[DpopKeyPool]): UIO[Unit] =
    for
      _ <- ZIO.logInfo(s"Warming up: ${urls(targets).mkString(", ")}")
      _ <- ZIO.foreachDiscard(1 to rounds): _ =>
        ZIO.foreachDiscard(urls(targets))(get(client, _))
      // One proof per round, with the key a session would use, so the signer is loaded and compiled.
      _ <- ZIO.foreachDiscard(dpop): pool =>
        ZIO
          .foreachDiscard(1 to rounds)(_ => pool.keyFor(0L).proof(Method.POST, urls(targets).head).ignore)
      _ <- ZIO.logInfo("Warm-up done")
    yield ()

  private def get(client: Client, url: String): UIO[Unit] =
    ZIO
      .scoped(client.request(Request.get(url)).flatMap(_.body.asString))
      .timeoutFail(new java.util.concurrent.TimeoutException(s"warm-up of $url"))(callTimeout)
      .unit
      .catchAll(error => ZIO.logWarning(s"Warm-up call to $url failed, continuing: $error"))
