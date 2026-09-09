package versola.e2e.support

import zio.*
import zio.http.*

/** A request the stub upstream received, kept in the shape a proxy test wants to assert on. */
case class UpstreamRequest(
    method: String,
    path: String,
    query: Map[String, Chunk[String]],
    headers: Map[String, String],
    body: String,
):
  def header(name: String): Option[String] =
    headers.get(name.toLowerCase)

  def queryParam(name: String): Option[String] =
    query.get(name).flatMap(_.headOption)

/** The resource server that sits behind the edge in the proxy tests.
  *
  * Nothing about the edge's own behaviour can be observed without one: a 403 tells you the
  * request was stopped, but only the upstream can tell you what a request that got through
  * actually looked like — which path it landed on, which headers survived, whether the
  * browser's session cookie leaked past the proxy.
  *
  * Every request is recorded and answered from a script the test sets beforehand, so a spec
  * can also make the upstream fail on purpose and check what the caller is told.
  */
final class UpstreamStub(
    val port: Int,
    received: Ref[Chunk[UpstreamRequest]],
    scripted: Ref[UpstreamStub.Reply],
):

  /** Everything the upstream has been sent since the last [[reset]], oldest first. */
  def requests: UIO[Chunk[UpstreamRequest]] = received.get

  /** The most recent request, or `None` when the edge never called through. */
  def lastRequest: UIO[Option[UpstreamRequest]] = received.get.map(_.lastOption)

  /** Forgets the recorded traffic and restores the default reply. Tests share one upstream,
    * so a spec that does not clear it would assert on its predecessor's requests.
    */
  def reset: UIO[Unit] = received.set(Chunk.empty) *> scripted.set(UpstreamStub.Reply())

  /** Makes the upstream answer the next requests this way. */
  def replyWith(
      status: Status = Status.Ok,
      body: String = """{"ok":true}""",
      headers: List[(String, String)] = Nil,
  ): UIO[Unit] =
    scripted.set(UpstreamStub.Reply(status, body, headers))

  private def record(path: Path, request: Request): Task[Response] =
    for
      body <- request.body.asString
      _ <- received.update(
        _ :+ UpstreamRequest(
          method = request.method.name,
          path = path.encode,
          query = request.url.queryParams.map,
          headers = request.headers.map(header => header.headerName.toLowerCase -> header.renderedValue).toMap,
          body = body,
        ),
      )
      reply <- scripted.get
    yield reply.headers.foldLeft(Response(status = reply.status, body = Body.fromString(reply.body))) {
      case (response, (name, value)) => response.addHeader(name, value)
    }

object UpstreamStub:

  case class Reply(
      status: Status = Status.Ok,
      body: String = """{"ok":true}""",
      headers: List[(String, String)] = Nil,
  )

  /** The origin the proxy specs register as their resource, and therefore the port the stub
    * has to claim. Fixed rather than ephemeral because central stores the URI and resolves a
    * token's audience by it, so the edge has to be able to reach exactly this address.
    */
  val port = 9104
  val uri = s"http://localhost:$port"

  /** Runs the stub for as long as the spec that acquired it. The server layer is composed in
    * rather than provided inside the effect, so its scope is the spec's and not the single
    * effect that starts it.
    */
  val live: ZLayer[Any, Throwable, UpstreamStub] =
    Server.defaultWithPort(port) >>> ZLayer.scoped[Server] {
      for
        received <- Ref.make(Chunk.empty[UpstreamRequest])
        scripted <- Ref.make(Reply())
        stub = UpstreamStub(port, received, scripted)
        routes = Routes(
          Method.ANY / trailing -> handler { (path: Path, request: Request) => stub.record(path, request) },
        ).sandbox
        _ <- Server.install(routes)
      yield stub
    }
