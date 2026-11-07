package versola.oauth.revocation

import versola.oauth.mtls.EdgeCertificateTrust
import versola.util.http.{Controller, Observability}
import versola.util.revocation.{Revocation, RevocationCursor}
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

import java.security.cert.X509Certificate
import scala.util.Try

/** `GET /revocations`: the revocations an edge pulls instead of waiting to be told (#560).
  *
  * Served on auth's mutual-TLS listener only (`MPORT`, `PostgresOAuthApp.mutualTlsRoutes`), never on the main
  * listener or `/service`. The caller is the edge whose certificate the handshake validated
  * ([[EdgeCertificateTrust.edgeOf]]): a certificate in a header is never read, so anything that can only speak HTTP to
  * this process is refused. A client's certificate, a certificate of an edge central no longer lists and a request on a
  * connection that carried no certificate are all `403`.
  *
  * What is served is limited to the caller's tenants and to the rows no tenant owns (`tenant_id IS NULL`: a session or a
  * user, see `V0033`). An edge learns nothing about the tokens of tenants it is not assigned to.
  */
object RevocationFeedController extends Controller:
  type Env = Tracing & EdgeCertificateTrust & AuthRevocationRepository

  /** What a request is served when it names no limit. */
  val DefaultLimit: Int = 5000

  /** The most one page holds, whatever is asked for: bounds what a single request makes auth read and hold. An edge
    * that asks for more is served this many and reads on from the cursor.
    */
  val MaxLimit: Int = 10000

  def routes: Routes[Env, Throwable] = Routes(feedEndpoint)

  case class Entry(key: String, exp: Long, before: Option[Long]) derives JsonCodec

  /** @param more whether the page filled the limit, which is the only evidence there is another one. Said here because
    *             the page holds only the rows this version decodes, so its length alone does not tell the reader.
    */
  case class Page(revocations: List[Entry], cursor: String, more: Boolean) derives JsonCodec

  val feedEndpoint =
    Method.GET / "revocations" -> handler { (request: Request) =>
      (for
        edge <- edgeOf(request)
        after <- ZIO.fromEither(cursorOf(request.queryParam("after")))
        limit <- ZIO.fromEither(limitOf(request.queryParam("limit")))
        _ <- Observability.setRouteLabel("trusted_edge", edge.id)
        page <- ZIO.serviceWithZIO[AuthRevocationRepository](_.feedSince(after, limit, edge.tenantIds))
        // An empty page hands back the cursor it was given, as received: the caller is already at the end
        // of what it may read, and the only position that cannot be wrong is the one it holds.
        cursor = page.last.map(_.encoded).getOrElse(request.queryParam("after").getOrElse(""))
      yield Response.json(Page(revocations = page.revocations.map(entry), cursor = cursor, more = page.hasMore).toJson))
        .catchAll:
          case Refusal.Forbidden =>
            Observability.setError(
              "access_denied",
              Some("not a trusted edge"),
            ).as(Response.json("""{"error":"access_denied"}""").status(Status.Forbidden))
          case Refusal.BadRequest(reason) =>
            Observability.setError("invalid_request", Some(reason)).as(Response.json("""{"error":"invalid_request"}""").status(Status.BadRequest))
          case error: Throwable => ZIO.fail(error)
    }

  private enum Refusal:
    case Forbidden
    case BadRequest(reason: String)

  private def edgeOf(request: Request): ZIO[EdgeCertificateTrust, Refusal, EdgeCertificateTrust.Edge] =
    request.remoteCertificate match
      case Some(certificate: X509Certificate) =>
        ZIO.serviceWithZIO[EdgeCertificateTrust](_.edgeOf(certificate)).someOrFail(Refusal.Forbidden)
      case _ => ZIO.fail(Refusal.Forbidden)

  private def entry(revocation: Revocation): Entry =
    Entry(
      key = revocation.key.encoded,
      exp = revocation.expiresAt.getEpochSecond,
      before = revocation.issuedBefore.map(_.getEpochSecond),
    )

  private def limitOf(raw: Option[String]): Either[Refusal, Int] =
    raw match
      case None => Right(DefaultLimit)
      case Some(value) =>
        Try(value.toInt).toOption.filter(_ > 0).map(math.min(_, MaxLimit)).toRight(Refusal.BadRequest("limit is not a positive integer"))

  /** The cursor is the one [[RevocationCursor.encoded]] writes, handed back as received; absent or empty is the
    * beginning of the list. */
  private def cursorOf(raw: Option[String]): Either[Refusal, RevocationCursor] =
    RevocationCursor.decode(raw.getOrElse("")).toRight(Refusal.BadRequest("after is not a cursor this endpoint issued"))
