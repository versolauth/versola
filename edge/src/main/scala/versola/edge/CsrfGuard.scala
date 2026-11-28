package versola.edge

import versola.edge.model.AuthorizationPreset
import zio.http.{Method, Request, URL}

/** What keeps another site from making a state-changing call with a browser's `EDGE_SESSION` cookie.
  *
  * `SameSite=Strict` does not: a site is a registrable domain, so any other subdomain of it is the same site, and what it sends
  * carries the cookie.
  * What a browser does tell the server is where the call comes from: `Origin` on every call that is not a `GET` or `HEAD` (Fetch,
  * "append a request Origin header"; its value is `null` when the page asked for no referrer), and `Sec-Fetch-Site` on every call of a
  * current browser (Fetch Metadata).
  *
  * A cookie-authenticated call with an unsafe method (RFC 9110 §9.2.1) is refused when the browser says it comes from somewhere
  * that is not ours, and goes on when it does not say (a script, a test, an old client: none of them is a browser holding someone
  * else's cookie). Ours is edge's own origin and the origins of the preset the session was made for (where its app runs, which is
  * where it is sent back to after login and logout).
  *
  *   - `Origin` present: it decides. `null` and anything that is not one of ours is refused.
  *   - else `Sec-Fetch-Site` present: `same-site` and `cross-site` are refused; `same-origin`, `none` and a value this does not know go
  *     on (Fetch Metadata §2.3: a server "SHOULD ignore this header if it contains an invalid value").
  *   - neither: goes on.
  *
  * A call with a bearer or DPoP token is not looked at: no browser attaches those by itself.
  */
object CsrfGuard:

  private val Safe: Set[Method] = Set(Method.GET, Method.HEAD, Method.OPTIONS, Method.TRACE)

  /** The origins a cookie call may come from: edge's own and those of the preset the session belongs to, if it still exists. */
  def allowedOrigins(edgeUrl: URL, preset: Option[AuthorizationPreset]): List[URL] =
    edgeUrl :: preset.toList.flatMap(p =>
      List(p.redirectUri.toUrl, p.postLoginRedirectUri.toUrl) ++ p.postLogoutRedirectUri.map(_.toUrl),
    )

  def isSafe(method: Method): Boolean = Safe.contains(method)

  /** What the headers of a call say about where it comes from. */
  enum Verdict:
    /** A safe method, or an origin that is ours. */
    case Allowed
    /** Neither `Origin` nor `Sec-Fetch-Site`: not a browser's call, goes on, and is counted so that it can be seen how often it happens. */
    case NoHeaders
    /** `Origin` says it comes from somewhere that is not ours (`null` included). */
    case RefusedOrigin
    /** No `Origin`, and `Sec-Fetch-Site` says `same-site` or `cross-site`. */
    case RefusedFetchSite

  /** Where the browser says this call comes from, against the origins in `allowed`. */
  def verdict(request: Request, allowed: List[URL]): Verdict =
    if isSafe(request.method) then Verdict.Allowed
    else
      request.rawHeader("Origin") match
        case Some(origin) =>
          if URL.decode(origin.trim).toOption.exists(url => allowed.exists(sameOrigin(url, _))) then Verdict.Allowed
          else Verdict.RefusedOrigin
        case None =>
          request.rawHeader("Sec-Fetch-Site") match
            case Some(site) if Set("same-site", "cross-site").contains(site.trim.toLowerCase) => Verdict.RefusedFetchSite
            case Some(_) => Verdict.Allowed
            case None => Verdict.NoHeaders

  def refused(verdict: Verdict): Boolean = verdict == Verdict.RefusedOrigin || verdict == Verdict.RefusedFetchSite

  /** Scheme, host and port, with the default port made explicit. An `Origin` is only these three, so a path on either side is ignored. */
  private def sameOrigin(a: URL, b: URL): Boolean =
    a.host.isDefined && a.scheme.isDefined &&
      a.scheme.map(_.encode.toLowerCase) == b.scheme.map(_.encode.toLowerCase) &&
      a.host.map(_.toLowerCase) == b.host.map(_.toLowerCase) &&
      a.portOrDefault == b.portOrDefault
