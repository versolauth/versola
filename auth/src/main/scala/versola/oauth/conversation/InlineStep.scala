package versola.oauth.conversation

import zio.json.*

/** What a submit answers when the page sent it with `Versola-Inline: 1` (the credential and OTP forms do, from script):
  * where a plain submit would have redirected, `next`, and, when the request has to prove itself first, the
  * `challenge` for the page to answer in the background. A page that cannot answer one just goes to `next`, where the
  * `proof` form does it in the open.
  *
  * Which challenge is asked for is the `kind`'s business and `params` is whatever that kind needs, so a visible widget
  * (a captcha's site key and the like) can be drawn by the same page where it is now solved without being seen.
  */
case class InlineStep(next: String, challenge: Option[InlineChallenge]) derives JsonCodec

case class InlineChallenge(kind: String, token: String, params: Map[String, String]) derives JsonCodec

object InlineStep:
  val Header = "Versola-Inline"
