package versola.e2e.support

import zio.*
import zio.http.*
import zio.http.Header.Authorization
import zio.json.*
import zio.json.ast.Json

import java.util.UUID

/** Thin HTTP client for central's admin API.
  *
  * Deliberately untyped: every verb answers an [[ApiResult]] carrying the raw status and body,
  * because most of what these tests check is exactly what a typed client would hide — the
  * status code a rejected write answers with, and the error document that comes back with it.
  *
  * The credentials are part of the client rather than of each call, so a test that checks how
  * an endpoint reacts to the wrong secret changes one thing ([[withCredentials]]) instead of
  * every request it makes.
  */
final class CentralApi(client: Client, val config: E2EConfig, credentials: Option[(String, String)]):

  /** The same API as seen by a caller presenting different Basic credentials. */
  def withCredentials(user: String, secret: String): CentralApi =
    CentralApi(client, config, Some(user -> secret))

  /** The same API as seen by a caller presenting no credentials at all. */
  def anonymous: CentralApi =
    CentralApi(client, config, None)

  def get(path: String, query: (String, String)*): Task[ApiResult] =
    send(Method.GET, path, query, None)

  def post(path: String, body: Json, query: (String, String)*): Task[ApiResult] =
    send(Method.POST, path, query, Some(body.toJson))

  def postEmpty(path: String, query: (String, String)*): Task[ApiResult] =
    send(Method.POST, path, query, None)

  def put(path: String, body: Json, query: (String, String)*): Task[ApiResult] =
    send(Method.PUT, path, query, Some(body.toJson))

  def patch(path: String, body: Json, query: (String, String)*): Task[ApiResult] =
    send(Method.PATCH, path, query, Some(body.toJson))

  def delete(path: String, query: (String, String)*): Task[ApiResult] =
    send(Method.DELETE, path, query, None)

  /** `DELETE` with a body — the OTP template endpoint identifies its target that way,
    * because its key is composite.
    */
  def deleteWithBody(path: String, body: Json): Task[ApiResult] =
    send(Method.DELETE, path, Nil, Some(body.toJson))

  /** Sends a body verbatim rather than through an encoder, so a test can present something
    * that is not valid JSON, or JSON of the wrong shape, without the client correcting it.
    */
  def raw(method: Method, path: String, body: String, query: (String, String)*): Task[ApiResult] =
    send(method, path, query, Some(body))

  private def send(
      method: Method,
      path: String,
      query: Seq[(String, String)],
      body: Option[String],
  ): Task[ApiResult] =
    for
      base <- ZIO.fromEither(URL.decode(s"${config.centralUrl}$path")).mapError(RuntimeException(_))
      request = Request(
        method = method,
        url = base.addQueryParams(query.toList),
        body = body.fold(Body.empty)(Body.fromString(_)),
      )
      withType = body.fold(request)(_ => request.addHeader(Header.ContentType(MediaType.application.json)))
      authorized = credentials.fold(withType)((user, secret) => withType.addHeader(Authorization.Basic(user, secret)))
      response <- Client.batched(authorized).provide(ZLayer.succeed(client))
      result <- ApiResult.of(response)
    yield result

object CentralApi:

  val live: ZLayer[Client & E2EConfig, Nothing, CentralApi] =
    ZLayer.fromFunction((client: Client, config: E2EConfig) =>
      CentralApi(client, config, Some("central" -> config.resourceSecret)),
    )

  // ── Unique identifiers ──────────────────────────────────────────────────
  //
  // Central's admin API has no per-test isolation: every spec writes to the same tenant of
  // the same database, and most of these entities are keyed by a caller-chosen id. Each
  // fixture therefore names itself uniquely, so a test never collides with a leftover from
  // an earlier run — and so a failed test's rows can never make a later one fail too.

  private def suffix: UIO[String] =
    ZIO.succeed(UUID.randomUUID().toString.replace("-", "").take(10))

  /** An id matching central's `^[a-z][a-z0-9-]*$` pattern (tenants, clients, resources, roles, edges). */
  def id(prefix: String): UIO[String] =
    suffix.map(s => s"$prefix-$s")

  /** A token matching central's `^[a-z][a-z0-9_]*$` pattern (scopes, claims, detail types). */
  def token(prefix: String): UIO[String] =
    suffix.map(s => s"${prefix}_$s")

  /** A permission matching `^[a-z][a-z0-9_]*([.:][a-z][a-z0-9_]*)*$`. */
  def permission(prefix: String): UIO[String] =
    suffix.map(s => s"$prefix:p_$s")

  def email(prefix: String = "user"): UIO[String] =
    suffix.map(s => s"$prefix-$s@example.test")

  def login(prefix: String = "user"): UIO[String] =
    suffix.map(s => s"$prefix-$s")

  /** A distinct, well-formed international number. Derived from a random UUID rather than a
    * counter so that parallel spec runs cannot mint the same one.
    */
  def phone: UIO[String] =
    ZIO.succeed(UUID.randomUUID()).map(uuid => f"+49157${uuid.getLeastSignificantBits.abs % 100_000_000L}%08d")

  def uuid: UIO[UUID] =
    ZIO.succeed(UUID.randomUUID())
