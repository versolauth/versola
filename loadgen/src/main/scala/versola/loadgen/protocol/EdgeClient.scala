package versola.loadgen.protocol

import zio.IO

/** The web/cookie path through edge (§8.4) -- entirely new code, not covered by the e2e client
  * (which never exercises `/login/{presetId}` -> `/complete`). Implemented by
  * [[HttpEdgeClient]]; the flow that sequences it is [[WebFlows]].
  *
  * One method per hop, as in [[AuthClient]]: §8.4 requires every hop to be separately timed, and
  * the timing lives in the flow layer, so a method that made two requests would make two hops
  * one measurement.
  *
  * The resource-proxy call itself is [[ActionClient]], which track B implements
  * ([[EdgeActionClient]]) because §8.6 needs it for the mobile flows too.
  */
trait EdgeClient extends ActionClient:
  /** §8.4 hop 1: `GET {edge}/login/{presetId}`. Edge mints the PKCE pair and the `state`, records
    * the pending login, and answers a redirect to auth's `/authorize`.
    *
    * `acrValues` is passed through as the `acr_values` query parameter, which edge's login
    * parameter whitelist (`EdgeController.loginParamWhitelist`) forwards onto the authorize URL
    * -- the only way a web login can ask for an assurance level, since the driver does not build
    * that URL itself.
    */
  def login(preset: PresetId, acrValues: Option[List[String]]): IO[ProtocolError, EdgeLoginStarted]

  /** §8.4 hop 2: the `GET` on the absolute URL edge just redirected to. The request goes to
    * auth, not to edge, but it belongs here rather than on [[AuthClient]]: every parameter on it
    * is edge's, so there is nothing for [[AuthClient.authorize]] to build, and following another
    * party's `Location` is exactly the browser behaviour this client emulates.
    *
    * Returns the `SSO_CONVERSATION` the conversation of hops 3-6 is then walked with.
    */
  def startConversation(started: EdgeLoginStarted): IO[ProtocolError, ConversationCookie]

  /** §8.4's last hop: `GET {edge}/complete?code=…&state=…`, which exchanges the code behind the
    * driver's back and answers the redirect that sets `EDGE_SESSION`.
    *
    * Both parameters come off the redirect auth ended the conversation with -- the `state` is
    * edge's own, echoed back.
    */
  def complete(state: String, code: AuthCode): IO[ProtocolError, EdgeCookie]

  /** `GET {edge}/complete?error=...&state=...`: the other redirect auth can end a web
    * conversation with, and the branch of edge's `/complete` that consumes the `pending_logins`
    * record the login created. A browser follows it for the same reason it follows the code.
    *
    * Not a way to report the refusal -- the flow fails with the SUT's `error` either way. This
    * exists so the refusal does not also leave a row behind in the SUT.
    */
  def completeError(state: String, error: String): IO[ProtocolError, Unit]

  /** `GET {edge}/logout/{presetId}`: the browser being handed on to auth's RP-initiated logout.
    *
    * The cookie is sent because a browser sends it -- `EDGE_SESSION` is `SameSite=Strict` and
    * this is a same-site navigation -- even though this endpoint does not read it. What actually
    * ends the edge session is [[endSession]]; a driver that stops here leaves a cookie edge will
    * still honour.
    *
    * Returns the `Location` edge redirected to rather than discarding it, because that URL is
    * where the SSO session is actually ended and it is not reconstructible: edge appends the
    * preset's `post_logout_redirect_uri` when it has one, and auth binds the confirmation token
    * to the exact value it was called with.
    */
  def logout(preset: PresetId, session: EdgeSession): IO[ProtocolError, String]

  /** `GET {edge}/logout/frontchannel` with the cookie: the hop that revokes the session edge
    * side. In a browser the OP triggers it from a hidden iframe on its logout page; the driver
    * has no iframe, so it makes the request the iframe would have made.
    *
    * Separate from [[logout]] because it is a separate hop and §8.4 measures hops, not
    * intentions.
    */
  def endSession(session: EdgeSession): IO[ProtocolError, Unit]
