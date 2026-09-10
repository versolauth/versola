package versola.loadgen.protocol

import zio.http.{Method, Status}

// Opaque wrappers over the handful of protocol-level strings that must never be interchanged by
// accident (a CSRF token passed where a conversation cookie was expected fails silently as a
// wrong-parameter bug, not a compile error, without this). Kept to plain `String` underneath --
// no validation here, that belongs to whatever produced the value (auth's response, or
// `PkceHelper`/`SoftAuthenticator` once track B ports them).

opaque type ConversationCookie = String
object ConversationCookie:
  def apply(value: String): ConversationCookie = value
  extension (c: ConversationCookie) def value: String = c

opaque type Csrf = String
object Csrf:
  def apply(value: String): Csrf = value
  extension (c: Csrf) def value: String = c

opaque type AuthCode = String
object AuthCode:
  def apply(value: String): AuthCode = value
  extension (c: AuthCode) def value: String = c

opaque type CodeVerifier = String
object CodeVerifier:
  def apply(value: String): CodeVerifier = value
  extension (c: CodeVerifier) def value: String = c

opaque type RefreshToken = String
object RefreshToken:
  def apply(value: String): RefreshToken = value
  extension (t: RefreshToken) def value: String = t

opaque type IdToken = String
object IdToken:
  def apply(value: String): IdToken = value
  extension (t: IdToken) def value: String = t

opaque type AccessToken = String
object AccessToken:
  def apply(value: String): AccessToken = value
  extension (t: AccessToken) def value: String = t

opaque type PresetId = String
object PresetId:
  def apply(value: String): PresetId = value
  extension (p: PresetId) def value: String = p

/** The edge cookie session (`EDGE_SESSION`, §8.4). Edge rotates this on refresh -- the driver
  * must adopt every `Set-Cookie` on every response, or the session dies mid-run and looks like a
  * phantom SUT failure (§8.4's warning).
  */
opaque type EdgeSession = String
object EdgeSession:
  def apply(value: String): EdgeSession = value
  extension (s: EdgeSession) def value: String = s

case class ClientCreds(clientId: String, clientSecret: Option[String])

case class Tokens(
    accessToken: AccessToken,
    refreshToken: Option[RefreshToken],
    idToken: Option[IdToken],
    expiresInSeconds: Long,
)

/** Result of the `authorize` convenience call (§4): generates PKCE + state, starts a new
  * conversation, and returns everything the caller needs for the rest of the flow.
  */
case class AuthorizeStarted(
    conversation: ConversationCookie,
    codeVerifier: CodeVerifier,
    state: String,
)

/** A fetched challenge page. `step`/`csrf` are `None` when the page carries neither (e.g. an
  * error page) -- callers that require one assert on it explicitly rather than this type
  * throwing, unlike the e2e original's `ChallengeResult.csrf` (§3.2).
  */
case class ChallengePage(
    conversation: ConversationCookie,
    html: String,
    step: Option[ConversationStep],
    csrf: Option[Csrf],
)

/** Outcome of a challenge submission: either the conversation advanced to another page, or it
  * redirected out (to the code redirect URI, an error redirect, or -- mid-flow -- to
  * `/challenge` again for the next step, which `Redirected` alone deliberately does not
  * distinguish; the caller re-fetches via `challenge` to see which).
  */
enum SubmitOutcome:
  case Redirected(location: String)
  case Rendered(page: ChallengePage)

case class EdgeLoginStarted(conversation: ConversationCookie, codeVerifier: CodeVerifier, state: String)

case class ActionCall(method: Method, path: String, body: Option[String])

/** What a business action is authenticated with. An ADT rather than two `Option`s on
  * [[EdgeClient.call]]: the web and mobile paths are mutually exclusive, and edge gives a bearer
  * token precedence over the cookie when both arrive, so a caller that sent both would silently
  * exercise the mobile path while believing it measured the web one.
  */
enum EdgeCredential:
  case Cookie(session: EdgeSession)
  case Bearer(token: AccessToken)

/** `rotatedSession` carries the `EDGE_SESSION` edge issued on this response, when it issued one
  * (a refresh happened, or it simply re-set the cookie) -- the caller must adopt it for its next
  * call or the session dies mid-run and reads as a phantom SUT failure (§8.4). Always `None` on
  * the bearer path, which has no cookie to rotate.
  */
case class ActionOutcome(status: Status, body: String, rotatedSession: Option[EdgeSession])
