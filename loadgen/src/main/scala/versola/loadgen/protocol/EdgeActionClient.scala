package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import zio.http.Header.Authorization
import zio.http.*
import zio.{Duration, IO, ZIO, ZLayer}

/** The business-action half of §8.6: a call through the edge's resource proxy, with either the
  * mobile bearer token or the web `EDGE_SESSION` cookie.
  *
  * Deliberately not an [[EdgeClient]] implementation. §8.4's `/login/{presetId}` ->
  * `/complete` -> cookie path is new code that belongs to track G (#276); this is the one call
  * §8.1-8.3 and §8.5 need to exercise a session once it exists, and track G composes it rather
  * than re-deriving the 401/403 handling below.
  */
final class EdgeActionClient(exchange: HttpExchange, resources: URL) extends ActionClient:
  import EdgeActionClient.*

  override def call(credential: EdgeCredential, action: ActionCall): IO[ProtocolError, ActionOutcome] =
    val request = Request(
      method = action.method,
      url = resources.copy(path = Path.decode(action.path)),
      body = action.body.fold(Body.empty)(Body.fromString(_)),
    )
    val withBody = action.body.fold(request)(_ => request.addHeader(jsonContentType))
    exchange.send(authenticate(withBody, credential)).flatMap(outcome(_, action, credential))

  private def authenticate(request: Request, credential: EdgeCredential): Request =
    credential match
      case EdgeCredential.Bearer(token) => request.addHeader(Authorization.Bearer(token.value))
      case EdgeCredential.Cookie(session) => request.addHeader(HttpExchange.cookieHeader(edgeSessionCookie, session.value))

  /** The three outcomes that are outcomes and not failures (§4): a step-up demand, a `403` a
    * `retail-basic` user is expected to collect, and an expired access token. Each is a branch
    * the scenario engine takes, and none of them touches the error budget.
    */
  private def outcome(received: Received, action: ActionCall, credential: EdgeCredential): IO[ProtocolError, ActionOutcome] =
    if received.status == Status.Forbidden then ZIO.fail(ProtocolError.Forbidden(action.path))
    else if received.status == Status.Unauthorized then
      stepUpAcrValues(received) match
        case Some(acrValues) => ZIO.fail(ProtocolError.StepUpRequired(acrValues, action.path))
        case None => ZIO.fail(ProtocolError.Unauthorized(action.path))
    else
      // Edge rotates EDGE_SESSION whenever it refreshes behind the cookie; a caller that does
      // not adopt the new value loses the session mid-run and reads it as an SUT failure (§8.4).
      val rotated = credential match
        case EdgeCredential.Cookie(_) => HttpExchange.setCookie(received.response, edgeSessionCookie).map(EdgeSession.apply)
        case EdgeCredential.Bearer(_) => None
      ZIO.succeed(ActionOutcome(received.status, received.body, rotated))

object EdgeActionClient:
  private val edgeSessionCookie = "EDGE_SESSION"
  private val jsonContentType = Header.ContentType(MediaType.application.json)

  private val insufficientAuthentication = "insufficient_user_authentication"
  private val acrValuesParameter = java.util.regex.Pattern.compile("""acr_values="([^"]*)"""")

  /** Edge answers a step-up demand with `401` and
    * `WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="..."`
    * (`EdgeService.proxy`). The `acr_values` parameter is space-delimited, as everywhere else in
    * OAuth. A plain `401` without that error is an expired token, not a step-up -- the two drive
    * completely different branches, so the discriminator is the error code, not the status.
    */
  private def stepUpAcrValues(received: Received): Option[List[String]] =
    received.response
      .rawHeader("WWW-Authenticate")
      .filter(_.contains(insufficientAuthentication))
      .map: challenge =>
        val matcher = acrValuesParameter.matcher(challenge)
        if matcher.find() then matcher.group(1).split(' ').iterator.filter(_.nonEmpty).toList else Nil

  def make(client: Client, targets: TargetsConfig, requestTimeout: Duration): IO[ProtocolError, ActionClient] =
    ZIO
      .fromEither(URL.decode(targets.edgeUrl).left.map(error => ProtocolError.Misconfigured(targets.edgeUrl + ": " + error.getMessage)))
      .map(url => EdgeActionClient(HttpExchange(client, requestTimeout), url))

  val live: ZLayer[Client & TargetsConfig, ProtocolError, ActionClient] =
    ZLayer.fromZIO:
      for
        client <- ZIO.service[Client]
        targets <- ZIO.service[TargetsConfig]
        actionClient <- make(client, targets, LoadgenHttpClient.requestTimeout)
      yield actionClient
