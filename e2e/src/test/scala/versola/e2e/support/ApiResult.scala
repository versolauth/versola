package versola.e2e.support

import zio.*
import zio.http.{Response, Status}
import zio.json.*
import zio.json.ast.Json

/** Raw outcome of an admin/API call.
  *
  * Status assertions are the point of most of these tests, so the body is kept as text and
  * only interpreted when a test asks for it — a 400 carrying `text/plain` and a 200 carrying
  * JSON go through the same type.
  */
case class ApiResult(response: Response, body: String):
  val status: Status = response.status

  def obj: Task[Json.Obj] =
    ZIO.fromEither(body.fromJson[Json.Obj])
      .mapError(error => RuntimeException(s"Expected a JSON object [$error]: $body"))

  def array: Task[Chunk[Json]] =
    ZIO.fromEither(body.fromJson[Json.Arr])
      .mapError(error => RuntimeException(s"Expected a JSON array [$error]: $body"))
      .map(_.elements)

  /** The elements of a top-level JSON array, as objects. */
  def objects: Task[Chunk[Json.Obj]] =
    array.map(_.collect { case o: Json.Obj => o })

  /** The elements of the named array member of a JSON object response, e.g. `tenants`. */
  def items(field: String): Task[Chunk[Json.Obj]] =
    obj.map(_.objs(field))

  def stringAt(field: String): Task[String] =
    obj.flatMap: o =>
      ZIO.fromOption(o.str(field))
        .orElseFail(RuntimeException(s"No string member '$field' in: $body"))

object ApiResult:
  def of(response: Response): Task[ApiResult] =
    response.body.asString.map(ApiResult(response, _))

/** Reading members out of a `Json.Obj` without pattern-matching at every call site. All of
  * these answer `None`/empty rather than failing, so a test asserting that a member is absent
  * reads the same way as one asserting its value.
  */
extension (json: Json.Obj)
  def str(field: String): Option[String] =
    json.fields.collectFirst { case (name, Json.Str(value)) if name == field => value }

  def num(field: String): Option[BigDecimal] =
    json.fields.collectFirst { case (name, Json.Num(value)) if name == field => BigDecimal(value) }

  def int(field: String): Option[Int] =
    num(field).map(_.toInt)

  def bool(field: String): Option[Boolean] =
    json.fields.collectFirst { case (name, Json.Bool(value)) if name == field => value }

  def obj(field: String): Option[Json.Obj] =
    json.fields.collectFirst { case (name, value: Json.Obj) if name == field => value }

  def arr(field: String): Chunk[Json] =
    json.fields.collectFirst { case (name, Json.Arr(values)) if name == field => values }
      .getOrElse(Chunk.empty)

  def objs(field: String): Chunk[Json.Obj] =
    arr(field).collect { case o: Json.Obj => o }

  def strings(field: String): Set[String] =
    arr(field).collect { case Json.Str(value) => value }.toSet

  /** Whether the member is present at all, including when it is `null`. */
  def has(field: String): Boolean =
    json.fields.exists((name, _) => name == field)

  def isNull(field: String): Boolean =
    json.fields.exists((name, value) => name == field && value == Json.Null)
