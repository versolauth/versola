package versola.loadgen.protocol

import zio.Duration
import zio.http.{Method, Status, URL}

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

/** Outcome of [[AuthClient.authorize]]: either a conversation actually started, or the SUT
  * recognized the supplied `SSO_SESSION` as already satisfying the request and answered a
  * silent reauthorization (design doc §7.4) -- a redirect straight to the code, with no
  * `SSO_CONVERSATION` cookie and no conversation to walk. `codeVerifier` is still required in
  * both cases: the code was issued for the `code_challenge` this call sent to `/authorize`
  * regardless of which path answered it.
  */
enum AuthorizeOutcome:
  case Started(started: AuthorizeStarted)
  case Authorized(code: AuthCode, codeVerifier: CodeVerifier)

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

  /** The same `window.__VERSOLA_FORM__` blob carries the logout confirmation's token, so the
    * one compiled pattern serves both pages rather than a second one being added for a form
    * that differs only in which fields it posts back.
    */
  def csrfOf(html: String): Option[Csrf] =
    val matcher = csrfField.matcher(html)
    if matcher.find() then Some(Csrf(matcher.group(1))) else None

/** Auth's logout confirmation page: what `GET /logout` renders when it is called with only a
  * session cookie and no `id_token_hint` -- which is every web logout, since edge holds the id
  * token and its redirect carries no hint.
  *
  * The URL and the two parameters travel with the token because auth binds the token to all of
  * them (`csrfToken` in `LogoutController`): they are read off the URL edge redirected to and
  * posted back unchanged, and altering any of them invalidates the confirmation.
  */
case class LogoutConfirmation(url: URL, csrf: Csrf, postLogoutRedirectUri: Option[String], state: Option[String])

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

/** What `GET {edge}/login/{presetId}` leaves the driver holding (§8.4 hop 1): where edge sent it
  * next, and the `state` edge minted for the login it just recorded.
  *
  * Deliberately neither of the two fields W1 gave this type. There is no `ConversationCookie`
  * yet -- hop 1 answers a redirect to auth and starts no conversation, which only hop 2 does --
  * and there is no `CodeVerifier` at all on this path: edge mints the PKCE pair, keeps the
  * verifier in `pending_logins` and exchanges the code itself, so a driver that held one would
  * be holding a value it invented and can never use.
  *
  * `state` is kept because it is the one thing the driver can check: auth must echo edge's own
  * `state` back, and a mismatch means this flow is about to complete another virtual user's
  * login. Nothing else in §8.4 would notice.
  */
case class EdgeLoginStarted(authorizeUrl: String, state: String)

/** An `EDGE_SESSION` cookie as edge just set it -- on `/complete` at login, or on a proxied
  * action when it refreshed behind the cookie (§8.4's "edge rotates the cookie on refresh").
  *
  * The `Max-Age` travels with the value rather than being left for the caller to guess from
  * `session.access-token-ttl`, because edge sets it from the *refresh* token's lifetime when it
  * has one, and it is what `vu_sessions.access_expires_at` must hold: that column is the
  * liveness boundary the driver's startup load filters a web session on (migration V0002), so
  * an expiry guessed long makes the driver resume dead sessions and report the resulting 401s
  * as the SUT's. `None` when the header carried no `Max-Age`, which leaves the decision with the
  * caller instead of inventing one here.
  */
case class EdgeCookie(session: EdgeSession, maxAge: Option[Duration])

object EdgeCookie:
  def of(cookie: zio.http.Cookie.Response): EdgeCookie = EdgeCookie(EdgeSession(cookie.content), cookie.maxAge)

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

  /** The mobile path again, with the token sender-constrained (RFC 9449). Carries the key rather
    * than a proof: `htm`/`htu` differ per call and `jti` must not repeat, so a proof is minted
    * at the call site and one held here would be refused as a replay on its second use.
    *
    * The key is the session's own for the session's whole life -- the token was bound to it at
    * `/token`, and edge checks every resource call against that binding.
    */
  case Dpop(token: AccessToken, key: DpopKey)

object EdgeCredential:
  /** The mobile credential for a token the run's mode decides the shape of. Every place a mobile
    * session takes a new access token -- a login, a refresh, a step-up -- has to make the same
    * choice, and one of them picking `Bearer` on a DPoP run leaves that session unbound for the
    * rest of its life while the campaign still reports the mode it was driven in.
    */
  def mobile(token: AccessToken, key: Option[DpopKey]): EdgeCredential =
    key.fold(EdgeCredential.Bearer(token))(EdgeCredential.Dpop(token, _))

/** `rotatedSession` carries the `EDGE_SESSION` edge issued on this response, when it issued one
  * (a refresh happened, or it simply re-set the cookie) -- the caller must adopt it for its next
  * call or the session dies mid-run and reads as a phantom SUT failure (§8.4). Always `None` on
  * the bearer path, which has no cookie to rotate.
  */
case class ActionOutcome(status: Status, body: String, rotatedSession: Option[EdgeCookie])
