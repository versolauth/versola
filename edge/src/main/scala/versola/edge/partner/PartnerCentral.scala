package versola.edge.partner

import versola.edge.{CentralSyncTokenService, EdgeConfig}
import versola.util.http.Observability
import zio.http.{Body, Client, Header, Headers, MediaType, Method, QueryParams, Request, Status}
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps}
import zio.{Chunk, IO, Task, URLayer, ZIO, ZLayer, durationInt}

/** The management routes of central's partner surface (`/edge/partner/...`, #962 §4), called by this edge for its console.
  *
  * The call is the edge's own (its edge-signed token and certificate, as the sync is) and carries the staff member's access token
  * in `Versola-Principal`, which is what central verifies and writes into its audit log as the actor. Nothing is read from the
  * configuration snapshot: a management call is answered by central or not at all.
  *
  * What central's answer means to the console, in one place:
  * - transport failures, timeouts, `5xx`, and `401` (central does not accept this edge's token or certificate: from the
  *   console's side central cannot be used) are [[PartnerCentral.Unavailable]], which the API answers `503 central_unavailable`;
  * - `403` names no reason in its body: it is `partner_suspended` (the partner's edges are refused everything) or
  *   `partner_principal_mismatch` (the staff token is not a live token of the partner's console client from this issuer). The two
  *   are told apart by asking `/edge/partner/me`, which has no principal: if that is refused as well the partner is suspended.
  */
trait PartnerCentral:
  def call(
      method: Method,
      path: List[String],
      query: Map[String, String],
      body: Option[Json],
      principal: Option[String],
  ): IO[PartnerCentral.Failure | Throwable, PartnerCentral.Answer]

object PartnerCentral:
  /** What central answered, for any status the API passes on (`2xx`, `400`, `404`, `409`, and `202`'s body). */
  case class Answer(status: Status, body: String):
    def json: Option[Json] = body.fromJson[Json].toOption
    def obj: Option[Json.Obj] = json.collect { case obj: Json.Obj => obj }
    def text: String = body.take(500)

  enum Failure:
    case Unavailable
    case Suspended
    case PrincipalMismatch

  /** `Versola-Principal`, the staff member's access token (central's `PartnerPrincipal.Header`). */
  val PrincipalHeader = "Versola-Principal"

  private val Timeout = 15.seconds

  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, PartnerCentral] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(httpClient: Client, config: EdgeConfig, tokens: CentralSyncTokenService) extends PartnerCentral:

    override def call(
        method: Method,
        path: List[String],
        query: Map[String, String],
        body: Option[Json],
        principal: Option[String],
    ): IO[Failure | Throwable, Answer] =
      send(method, path, query, body, principal).flatMap: answer =>
        answer.status match
          case Status.Forbidden => disambiguate.flatMap(failure => ZIO.fail(failure))
          case Status.Unauthorized =>
            Observability.setError("central_refused_edge", Some("central answered 401 to a management call")) *> ZIO.fail(Failure.Unavailable)
          case status if status.isServerError =>
            Observability.setError("central_unavailable", Some(s"central answered ${status.code}")) *> ZIO.fail(Failure.Unavailable)
          case _ => ZIO.succeed(answer)

    /** `GET /edge/partner/me`, which has no principal: refused too means the partner is suspended, answered means the staff token
      * is the problem. When even that cannot be asked, central is not there. */
    private def disambiguate: IO[Failure | Throwable, Failure] =
      send(Method.GET, List("me"), Map.empty, None, None).foldZIO(
        _ => Observability.setError("central_unavailable") *> ZIO.succeed(Failure.Unavailable),
        answer =>
          answer.status match
            case Status.Forbidden => Observability.setError("partner_suspended").as(Failure.Suspended)
            case status if status.isSuccess => Observability.setError("partner_principal_mismatch").as(Failure.PrincipalMismatch)
            case _ => Observability.setError("central_unavailable").as(Failure.Unavailable),
      )

    private def send(
        method: Method,
        path: List[String],
        query: Map[String, String],
        body: Option[Json],
        principal: Option[String],
    ): IO[Failure | Throwable, Answer] =
      (for
        token <- tokens.getToken
        url = path.foldLeft(config.partnerUrl)(_ / _).copy(queryParams = QueryParams(query.toSeq.map((name, value) => name -> Chunk(value))*))
        base = Request(method = method, url = url, headers = Headers(Header.Authorization.Bearer(token)))
        withPrincipal = principal.fold(base)(token => base.addHeader(Header.Custom(PrincipalHeader, token)))
        request = body.fold(withPrincipal)(json =>
          withPrincipal.copy(body = Body.fromString(json.toJson)).addHeader(Header.ContentType(MediaType.application.json)),
        )
        answer <-
          ZIO.scoped(tokens.connection(httpClient).request(request).flatMap(response => response.body.asString.map(Answer(response.status, _))))
            .timeoutFail(Failure.Unavailable)(Timeout)
      yield answer).catchAll:
        case failure: Failure => ZIO.fail(failure)
        case error: Throwable =>
          Observability.setError("central_unavailable", Some(error.getClass.getSimpleName)) *> ZIO.fail(Failure.Unavailable)
