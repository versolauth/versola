package versola.oauth.conversation

import zio.json.*

/** What a submit answers when it was sent from script (see [[ProofInput]]): the `challenge` to answer, with nothing
  * sent, or where a plain submit would have redirected, `next`, and `otp` when that is the step where a code was just
  * sent, which the code page, if it is the one that asked, shows in place without leaving.
  *
  * Which challenge is asked for is the `kind`'s business and `params` is whatever that kind needs, so a visible widget
  * (a captcha's site key and the like) can be drawn by the same page where a proof of work is now solved unseen.
  */
case class InlineStep(next: Option[String], challenge: Option[InlineChallenge], otp: Boolean) derives JsonCodec

case class InlineChallenge(kind: String, token: String, params: Map[String, String]) derives JsonCodec
