package versola.edge

import versola.util.http.Tier
import zio.http.Method

/** What each route of the edge is worth when the pod is full, see [[versola.util.http.Tier]].
  *
  * Refused first, `Start`: work that has not begun and is the most expensive the edge serves, a new login.
  * Then `Continuation`: work the user has already started or must finish (code exchange, the refresh behind a native app's
  * token call, logout). Last, `Critical`: a user already inside, the proxied calls. A refresh made while serving a proxied
  * call is part of that call and ranks with it; only the native `token` endpoint is a refresh of its own.
  * `Never`: what keeps the pod and the system consistent -- config sync from central and the back-channel logout, whose
  * refusal would leave a session alive that the identity provider has ended.
  *
  * Every route of the edge is named here; [[EdgeAdmissionSpec]] fails when a route is added and not classified, because a
  * route that falls to the default is a route nobody decided about.
  */
object EdgeAdmission:
  def tierOf(method: Method, pattern: String): Tier = (method, pattern) match
    case (_, p) if p.startsWith("/service/") => Tier.Never
    case (_, "/logout/backchannel") => Tier.Never
    case (_, p) if p.startsWith("/login/") => Tier.Start
    case (_, p) if p.startsWith("/native/start/") => Tier.Start
    case (_, "/complete") => Tier.Continuation
    case (_, p) if p.startsWith("/native/complete/") || p.startsWith("/native/token/") || p.startsWith("/native/revoke/") =>
      Tier.Continuation
    case (_, "/logout/frontchannel") => Tier.Continuation
    case (_, p) if p.startsWith("/logout/") => Tier.Continuation
    case (_, "/permissions/me") => Tier.Critical
    case (_, p) if p.startsWith("/resources/") => Tier.Critical
    case _ => Tier.Critical

  /** The route patterns [[tierOf]] names explicitly; anything else is the default. */
  def isNamed(pattern: String): Boolean =
    pattern.startsWith("/service/") || pattern.startsWith("/logout/") || pattern.startsWith("/login/") ||
      pattern.startsWith("/native/") || pattern == "/complete" || pattern == "/permissions/me" ||
      pattern.startsWith("/resources/")
