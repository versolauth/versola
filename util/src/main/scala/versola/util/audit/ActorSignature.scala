package versola.util.audit

import versola.util.{Base64, JWT}
import zio.http.QueryParams
import zio.json.ast.Json
import zio.json.{DeriveJsonDecoder, JsonDecoder}
import zio.{Clock, Duration, IO, Task, ZIO, durationInt}

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, PrivateKey}

/** The names of the query parameters edge adds to a call it forwards to central to say who made it (#469): the administrator's id, the
  * tenant and the client of the token, the session, the address the call came from, and the signature that vouches for them. */
object ActorParams:
  /** The `sub` of the caller's token. */
  val Id = "actorId"

  /** The tenant of the caller's token. */
  val Tenant = "actorTenantId"

  /** The client the caller's token was issued to: equal to the actor for a client credentials token, whose subject is the client. */
  val Client = "actorClientId"

  /** The session the caller's token belongs to, when it carries one. */
  val Session = "actorSessionId"

  /** The address the call came from, as the edge that took it found it (its `client-ip` setting). */
  val Ip = "actorIp"

  /** [[ActorSignature]], over the whole query. */
  val Signature = "actorSignature"

  /** What a call states of its actor, that is: all but the signature. */
  val stated: Set[String] = Set(Id, Tenant, Client, Session, Ip)

/** What lets central tell what edge said of the caller of a call from what a caller that reaches central directly says: the signature
  * edge adds, a compact JWS under the edge's own key (the one central holds the public half of in the edge's registration, with the
  * previous one while the edge rotates), sent as the query parameter [[ActorParams.Signature]].
  *
  * It covers the method, the path and every other query parameter of the call (so the actor parameters, and also the ones that name
  * what the call acts on: the signature of a call is of no use on another route or for another target), and is valid for [[Lifetime]].
  * It is not a signature of the request body: it says who edge says the caller is, for this call, now; a copy of it cannot make
  * another caller of this route the administrator beyond that window, and it cannot be moved to another route or to other parameters.
  * The type of the JWT ([[JWT.Type.ActorSignature]]) is its own, and the edge's key is the one it signs everything it says to central
  * with, so a signature is not taken for any of the others, nor they for it.
  *
  * The JWS header names the edge (`edge_id`, as the token an edge syncs with does) and the key (`kid`).
  */
object ActorSignature:
  /** How long a signature is valid: a call is forwarded the moment it is signed. */
  val Lifetime: Duration = 60.seconds

  /** What a signature may claim at most between its issue and its expiry. */
  private val MaxLifetime: Duration = 2.minutes

  /** How far in the future its issue time may be: the clocks of edge and central are not the same. */
  private val Skew: Duration = 30.seconds

  case class Invalid(reason: String)

  private case class Claims(m: String, p: String, q: String, iat: Long, exp: Long)
  private given JsonDecoder[Claims] = DeriveJsonDecoder.gen[Claims]

  def sign(edgeId: String, keyId: String, privateKey: PrivateKey, method: String, path: String, query: QueryParams): Task[String] =
    JWT.serialize(
      claims = JWT.Claims(
        issuer = edgeId,
        subject = "actor",
        audience = List("central"),
        custom = Json.Obj("m" -> Json.Str(method), "p" -> Json.Str(path), "q" -> Json.Str(digest(query))),
      ),
      ttl = Lifetime,
      signature = JWT.Signature.Asymmetric(algorithm = JWT.Algorithm.RS256, keyId = keyId, privateKey = privateKey),
      typ = JWT.Type.ActorSignature,
      headers = Map("edge_id" -> edgeId),
    )

  /** The edge the signature names, to be looked up for the keys to [[verify]] it with. Nothing is trusted yet. */
  def edgeOf(token: String): IO[Invalid, String] =
    JWT.parseHeader[EdgeHeader](token).map(_.edgeId).mapError(_ => Invalid("header"))

  private case class EdgeHeader(edgeId: String)
  private given JsonDecoder[EdgeHeader] =
    JsonDecoder[Json.Obj].mapOrFail(obj => obj.get("edge_id").flatMap(_.asString).map(EdgeHeader(_)).toRight("no edge_id"))

  /** Whether `token` is a signature by one of `keys` of exactly this call: its method, its path and its query but the signature. */
  def verify(token: String, keys: JWT.PublicKeys, method: String, path: String, query: QueryParams): IO[Invalid, Unit] =
    for
      claims <- JWT.deserialize[Claims](token, keys, JWT.Type.ActorSignature).mapError(error => Invalid(error.toString))
      now <- Clock.instant
      _ <- ZIO.fail(Invalid("lifetime")).when(claims.exp - claims.iat > MaxLifetime.getSeconds || claims.iat > now.getEpochSecond + Skew.getSeconds)
      _ <- ZIO.fail(Invalid("call")).unless(claims.m == method && claims.p == path && claims.q == digest(query))
    yield ()

  /** The path as signed: its non-empty segments, so that a trailing slash is not the difference between two calls. */
  def pathOf(segments: Iterable[String]): String = segments.filter(_.nonEmpty).mkString("/", "/", "")

  /** The parameters of a query but the signature, in an order of their own and with the length of every name and value, so that no two
    * different queries have the same text. */
  private def canonical(query: QueryParams): String =
    query.map.toList
      .filterNot((name, _) => name == ActorParams.Signature)
      // `a=` is read by a server as `a` with no value, or with an empty one, depending on how it was written: an empty value is not
      // told from none, so a parameter that has only empty values is not in the call
      .map((name, values) => name -> values.toList.filter(_.nonEmpty))
      .filter((_, values) => values.nonEmpty)
      .sortBy(_._1)
      .map: (name, values) =>
        s"${name.length}:$name=" + values.map(value => s"${value.length}:$value").mkString(",")
      .mkString(";")

  private def digest(query: QueryParams): String =
    Base64.urlEncode(MessageDigest.getInstance("SHA-256").digest(canonical(query).getBytes(StandardCharsets.UTF_8)))
