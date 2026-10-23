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
final class CentralApi(
    client: Client,
    val config: E2EConfig,
    credentials: Option[(String, String)],
    bearer: Option[String] = None,
    /** Set for the calls made to central's mutual-TLS listener, where the edge surface is served:
      * what the connection presents, if anything. */
    edgeSurface: Option[Option[ClientSSLConfig]] = None,
    /** Who edge says the admin is (see `AntifraudListController`): sent as query parameters of a `GET` and as body fields of the rest. */
    admin: Option[(String, String)] = None,
):

  /** The same API as seen by an admin whom edge has identified (`adminSubject`, `adminTenant`, which edge adds from the verified
    * token and which overwrite what a caller sent); `tenant` is `default` for an operator. */
  def asAdmin(subject: String, tenant: String): CentralApi =
    CentralApi(client, config, credentials, bearer, edgeSurface, Some(subject -> tenant))

  /** The same API as seen by a caller presenting different Basic credentials. */
  def withCredentials(user: String, secret: String): CentralApi =
    CentralApi(client, config, Some(user -> secret))

  /** The same API as seen by a caller presenting no credentials at all. */
  def anonymous: CentralApi =
    CentralApi(client, config, None)

  /** The same API as seen by a caller presenting a bearer token instead of the admin
    * credential -- an edge's own sync token (#554), which is what the `/edge` surface wants and what
    * every other route refuses. */
  def withBearer(token: String): CentralApi =
    CentralApi(client, config, None, Some(token))

  /** The edge surface as an edge sees it (#554): over central's own mutual-TLS listener, `ssl` being what
    * the handshake presents -- `None` presents no certificate, which that listener refuses before
    * any request is read -- with the edge's signed token if it has one. */
  def onEdgeSurface(
      token: Option[String],
      ssl: Option[ClientSSLConfig],
      credentials: Option[(String, String)] = None,
  ): CentralApi =
    CentralApi(client, config, credentials, token, Some(ssl))

  def get(path: String, query: (String, String)*): Task[ApiResult] =
    send(Method.GET, path, query, None)

  def post(path: String, body: Json, query: (String, String)*): Task[ApiResult] =
    (path, body) match
      case (ClientEndpoints.Path, flat: Json.Obj) =>
        val (kindPath, typed) = ClientEndpoints.registration(flat)
        send(Method.POST, kindPath, query, Some(typed.toJson))
      case _ => send(Method.POST, path, query, Some(body.toJson))

  def postEmpty(path: String, query: (String, String)*): Task[ApiResult] =
    send(Method.POST, path, query, None)

  def put(path: String, body: Json, query: (String, String)*): Task[ApiResult] =
    (path, body) match
      case (ClientEndpoints.Path, flat: Json.Obj) =>
        // The first kind whose endpoint does not say the client is of another kind.
        ZIO.foldLeft(ClientEndpoints.updates(flat))(Option.empty[ApiResult]):
          case (done @ Some(result), _) if result.status != Status.Conflict => ZIO.succeed(done)
          case (_, (kindPath, typed)) => send(Method.PUT, kindPath, query, Some(typed.toJson)).asSome
        .map(_.get)
      case _ => send(Method.PUT, path, query, Some(body.toJson))

  def patch(path: String, body: Json, query: (String, String)*): Task[ApiResult] =
    send(Method.PATCH, path, query, Some(body.toJson))

  def delete(path: String, query: (String, String)*): Task[ApiResult] =
    send(Method.DELETE, path, query, None)

  /** `DELETE` with a body — the OTP template endpoint identifies its target that way,
    * because its key is composite.
    */
  def deleteWithBody(path: String, body: Json): Task[ApiResult] =
    send(Method.DELETE, path, Nil, Some(body.toJson))

  /** Drains central's user outbox into auth.
    *
    * A user created through the admin API exists in central's index immediately but only
    * reaches auth when the outbox is dispatched, and auth is where claims and credentials
    * live. Anything a test wants to write against the user itself has to wait for this.
    */
  def flushUserOutbox: Task[ApiResult] =
    postEmpty("/service/users/outbox/flush")

  /** Sends a body verbatim rather than through an encoder, so a test can present something
    * that is not valid JSON, or JSON of the wrong shape, without the client correcting it.
    */
  def raw(method: Method, path: String, body: String, query: (String, String)*): Task[ApiResult] =
    send(method, path, query, Some(body))

  /** What edge does to a `GET`: puts the caller in the query, replacing a parameter of the same name. */
  private def callerQuery(method: Method, query: List[(String, String)]): List[(String, String)] =
    admin.filter(_ => method == Method.GET).fold(query): (subject, tenant) =>
      query.filterNot((name, _) => name == "adminSubject" || name == "adminTenant") ++
        List("adminSubject" -> subject, "adminTenant" -> tenant)

  /** What edge does to any other call that has a JSON object body: puts the caller in it. A call without a body gets one
    * (`{}`) so that the caller can be put in it; real edge does not, which is why central refuses such a call. */
  private def callerBody(method: Method, body: Option[String]): Option[String] =
    admin.filter(_ => method != Method.GET).fold(body): (subject, tenant) =>
      val fields = body.flatMap(_.fromJson[Json.Obj].toOption).fold(Chunk.empty[(String, Json)])(_.fields)
      Some(
        Json.Obj(
          fields.filterNot((name, _) => name == "adminSubject" || name == "adminTenant") ++
            Chunk("adminSubject" -> Json.Str(subject), "adminTenant" -> Json.Str(tenant)),
        ).toJson,
      )

  private def send(
      method: Method,
      path: String,
      query: Seq[(String, String)],
      body: Option[String],
  ): Task[ApiResult] =
    for
      origin = if edgeSurface.isDefined then config.centralMutualTlsUrl else config.centralUrl
      base <- ZIO.fromEither(URL.decode(s"$origin$path")).mapError(RuntimeException(_))
      request = Request(
        method = method,
        url = base.addQueryParams(callerQuery(method, query.toList)),
        body = callerBody(method, body).fold(Body.empty)(Body.fromString(_)),
      )
      withType = callerBody(method, body).fold(request)(_ => request.addHeader(Header.ContentType(MediaType.application.json)))
      withBasic = credentials.fold(withType)((user, secret) => withType.addHeader(Authorization.Basic(user, secret)))
      authorized = bearer.fold(withBasic)(token => withBasic.addHeader(Authorization.Bearer(token)))
      connection = edgeSurface.flatten.fold(client)(client.ssl(_))
      // Without a certificate the connection still has to trust central's own, or it would fail on that
      // instead of on the missing certificate this exists to show.
      pinned =
        if edgeSurface.exists(_.isEmpty) then client.ssl(ClientSSLConfig.FromCertFile(config.centralMutualTlsServerCertificate)) else connection
      response <- Client.batched(authorized).provide(ZLayer.succeed(pinned))
      result <- ApiResult.of(response)
    yield result

object CentralApi:

  /** Creates the suite's `standard` tenant first -- see [[SecurityProfiles]]. */
  val live: ZLayer[Client & E2EConfig, Nothing, CentralApi] =
    ZLayer.fromZIO(
      for
        client <- ZIO.service[Client]
        config <- ZIO.service[E2EConfig]
        api = CentralApi(client, config, Some("central" -> config.resourceSecret))
        _ <- SecurityProfiles.ensureSuiteTenant(api).orDie
      yield api,
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

  /** A permission matching `^[a-z][a-z0-9_]*(:[a-z][a-z0-9_]*)*$`. */
  def permission(prefix: String): UIO[String] =
    suffix.map(s => s"$prefix:p_$s")

  def email(prefix: String = "user"): UIO[String] =
    suffix.map(s => s"$prefix-$s@example.test")

  def login(prefix: String = "user"): UIO[String] =
    suffix.map(s => s"$prefix-$s")

  /** A distinct German mobile number. Central parses these with libphonenumber and rejects
    * anything of the wrong length, so the prefix and the digit count are both load-bearing.
    * Derived from a random UUID rather than a counter so parallel runs cannot collide.
    */
  def phone: UIO[String] =
    ZIO.succeed(UUID.randomUUID()).map(uuid => f"+49151${uuid.getLeastSignificantBits.abs % 100_000_000L}%08d")

  def uuid: UIO[UUID] =
    ZIO.succeed(UUID.randomUUID())
