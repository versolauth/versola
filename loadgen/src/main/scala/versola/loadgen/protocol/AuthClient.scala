package versola.loadgen.protocol

import zio.IO

/** Everything a virtual user's device can do against auth directly -- the `mobile-*` clients of
  * §8.1-8.3. No admin calls, no assertions: a driver never has more surface than a real client
  * app would (see versola-loadgen-dev-spec.md §4).
  *
  * Every method here keeps the exact URL, form fields, header set and cookie names the e2e
  * `OAuthClient` uses -- that is the hard-won protocol knowledge (§3.2). Implementations land
  * with track B; this is the interface every other track (scenario engine, calibration harness)
  * compiles against in the meantime.
  */
trait AuthClient:
  /** `sessionCookie` is the `SSO_SESSION` from a prior [[SubmitOutcome.Redirected]] (§3.2), sent
    * as a cookie on this request when present. Passing it is what makes silent reauthorization
    * and an ACR step-up (§7.4: "re-run `/authorize` ... on the same SSO session") land on the
    * caller's own existing session instead of starting a fresh one.
    */
  def authorize(
      scope: String,
      clientId: Option[String],
      acrValues: Option[List[String]],
      sessionCookie: Option[SsoSession],
  ): IO[ProtocolError, AuthorizeStarted]

  def challenge(conversation: ConversationCookie): IO[ProtocolError, ChallengePage]

  def submitPhone(conversation: ConversationCookie, phone: String, csrf: Csrf): IO[ProtocolError, SubmitOutcome]

  def submitOtp(conversation: ConversationCookie, code: String, csrf: Csrf): IO[ProtocolError, SubmitOutcome]

  def submitSetPassword(
      conversation: ConversationCookie,
      password: String,
      csrf: Csrf,
  ): IO[ProtocolError, SubmitOutcome]

  def submitLoginPassword(
      conversation: ConversationCookie,
      login: String,
      password: String,
      csrf: Csrf,
  ): IO[ProtocolError, SubmitOutcome]

  def passkeyOptions(conversation: ConversationCookie): IO[ProtocolError, String]

  def submitPasskeyAssertion(
      conversation: ConversationCookie,
      assertionJson: String,
      csrf: Csrf,
  ): IO[ProtocolError, SubmitOutcome]

  def exchangeCode(code: AuthCode, verifier: CodeVerifier, client: ClientCreds): IO[ProtocolError, Tokens]

  def exchangeRefresh(token: RefreshToken, client: ClientCreds): IO[ProtocolError, Tokens]

  def logout(idToken: IdToken): IO[ProtocolError, Unit]
