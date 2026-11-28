package versola.user.move

import zio.http.{Header, Response, Status}
import zio.json.*
import zio.json.ast.Json

/** What an endpoint says about a user who is not here (versolauth/versola#1051). One place, so that the bodies and headers are the
  * same at every endpoint that answers them; the endpoint's own error type carries the refusal as far as its controller. */
object DepartureResponses:
  /** `421` with exactly `{"error":"moved","cell":"<id>"}`: what an edge reads to ask the cell the user is in. */
  def moved(cell: String): Response =
    Response.json(Json.Obj("error" -> Json.Str("moved"), "cell" -> Json.Str(cell)).toJson).status(Status.MisdirectedRequest)

  /** `503` with `Retry-After`, and the body `{"error":"temporarily_unavailable", ...}` the OAuth endpoints answer in. */
  def leaving: Response =
    Response
      .json(
        Json.Obj(
          "error" -> Json.Str("temporarily_unavailable"),
          "error_description" -> Json.Str("The server is temporarily unable to handle the request"),
        ).toJson,
      )
      .status(Status.ServiceUnavailable)
      .addHeader(Header.Custom("Retry-After", DepartureRefusal.RetryAfterSeconds.toString))

  /** What an endpoint of a protocol says for a refusal. */
  def of(refusal: DepartureRefusal): Response = refusal match
    case DepartureRefusal.Leaving => leaving
    case DepartureRefusal.Moved(cell) => moved(cell)
