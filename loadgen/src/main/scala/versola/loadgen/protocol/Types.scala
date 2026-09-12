package versola.loadgen.protocol

import zio.http.{Method, Status}

// Opaque wrappers over the handful of protocol-level strings that must never be interchanged by
// accident (a CSRF token passed where a conversation cookie was expected fails silently as a
// wrong-parameter bug, not a compile error, without this). Kept to plain `String` underneath --
// no validation here, that belongs to whatever produced the value (auth's response, or
// [[Pkce]]/[[SoftAuthenticator]]).

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

/** The auth-side `SSO_SESSION` cookie (§3.2, §7.4). Set once a conversation completes; a driver
  * must hold onto it to re-run `/authorize` on the *same* SSO session for a silent
  * reauthorization or an ACR step-up (§7.4: "re-run `/authorize` with `acr_values` on the same
  * SSO session") -- without it, those flows have no way to avoid restarting at credentials.
  */
opaque type SsoSession = String
object SsoSession:
  def apply(value: String): SsoSession = value
  extension (s: SsoSession) def value: String = s

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

object ChallengePage:
  /** The form state auth inlines into the page as `window.__VERSOLA_FORM__ = {...}` carries the
    * CSRF token; this picks it out of the raw body.
    *
    * Compiled once, here, rather than per page as `ChallengeResult.csrf` does -- the single
    * worst hot-path defect in the e2e client (§3.2). A regex over the body and not an HTML
    * parser is a requirement, not an optimization (design doc §6.3, ~40x cheaper), and the
    * match is found in the head of the document, so the inlined script bundle further down is
    * never scanned.
    */
  private val csrfField = java.util.regex.Pattern.compile("\"csrf\"\\s*:\\s*\"([^\"]+)\"")

  def parse(conversation: ConversationCookie, html: String): ChallengePage =
    val matcher = csrfField.matcher(html)
    val csrf = if matcher.find() then Some(Csrf(matcher.group(1))) else None
    ChallengePage(conversation, html, ConversationStep.fromHtml(html), csrf)

/** Outcome of a challenge submission: either the conversation advanced to another page, or it
  * redirected out (to the code redirect URI, an error redirect, or -- mid-flow -- to
  * `/challenge` again for the next step, which `Redirected` alone deliberately does not
  * distinguish; the caller re-fetches via `challenge` to see which).
  *
  * `ssoSession` carries the `SSO_SESSION` cookie when this response set one (a completed
  * conversation, §3.2) -- `None` on every intermediate redirect. The caller must retain it and
  * feed it back into a later [[AuthClient.authorize]] call to reauthorize silently or step up on
  * the same SSO session (§7.4); without it, both flows would incorrectly restart at credentials.
  */
enum SubmitOutcome:
  case Redirected(location: String, ssoSession: Option[SsoSession])
  case Rendered(page: ChallengePage)

case class EdgeLoginStarted(conversation: ConversationCookie, codeVerifier: CodeVerifier, state: String)

/** `path` is the whole path under the edge origin, including the `/resources/{resourceId}`
  * prefix of §8.6 (`/resources/core/accounts`) -- the resource id is not a separate field
  * because §5's `BusinessActionConfig` does not carry one either, and splitting it here would
  * mean re-joining it on every call.
  */
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
