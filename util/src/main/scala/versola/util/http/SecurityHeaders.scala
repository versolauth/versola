package versola.util.http

import zio.*
import zio.http.*
import zio.http.codec.PathCodec

/** Response headers every application surface sets (#473): what a browser needs to be told so
  * that a login page cannot be framed, a JSON body sniffed into script, or a URL leaked to the
  * next site through `Referer`.
  *
  * A header a handler already set is left alone -- the handler knows its own response better.
  * Applied to the application servers (main, additional, mutual-TLS), not the diagnostics one,
  * which serves no browser.
  *
  * Deliberately absent: a `default-src` Content-Security-Policy and `form-action`. A page
  * restricted to `'self'` breaks the client logos and fonts a tenant configures, and `form-action
  * 'self'` blocks a form whose POST is answered with a redirect to the client's own
  * `redirect_uri` (Chromium applies it across redirects). Those need a report-only rollout
  * against real pages first; what is here is what cannot break one.
  */
object SecurityHeaders:

  /** One year, the usual floor for preload eligibility; `includeSubDomains` because every host
    * under the issuer's domain is expected to be https-only. Sent only over https (below), where
    * a browser acts on it -- RFC 6797 §8.1 has it ignored over plain http, so this only avoids
    * advertising a policy the connection cannot carry. */
  val StrictTransportSecurity: String = "max-age=31536000; includeSubDomains"

  /** Not framed by anyone, no `<base>` rewriting relative URLs, no plugins. `frame-ancestors`
    * is the CSP replacement for `X-Frame-Options`, which is sent too for browsers that predate
    * it. Only on HTML: a JSON response is never rendered, so a framing policy on it is noise. */
  val HtmlContentSecurityPolicy: String = "frame-ancestors 'none'; base-uri 'none'; object-src 'none'"

  private val always: List[Header.Custom] = List(
    Header.Custom("X-Content-Type-Options", "nosniff"),
    Header.Custom("Referrer-Policy", "no-referrer"),
    Header.Custom("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"),
  )

  private val html: List[Header.Custom] = List(
    Header.Custom("Content-Security-Policy", HtmlContentSecurityPolicy),
    Header.Custom("X-Frame-Options", "DENY"),
  )

  private def isHtml(response: Response): Boolean =
    response.header(Header.ContentType).exists(_.mediaType.subType.equalsIgnoreCase("html"))

  private def overHttps(request: Request, directTls: Boolean): Boolean =
    directTls ||
      request.url.scheme.contains(Scheme.HTTPS) ||
      request.headers.get("X-Forwarded-Proto").exists(_.equalsIgnoreCase("https"))

  /** @param directTls the listener terminates TLS itself, so the request target carries no scheme
    *                  and there is no proxy to send `X-Forwarded-Proto` -- the mutual-TLS port. */
  def apply(request: Request, response: Response, directTls: Boolean = false): Response =
    val wanted =
      always ++
        (if isHtml(response) then html else Nil) ++
        (if overHttps(request, directTls) then List(Header.Custom("Strict-Transport-Security", StrictTransportSecurity)) else Nil)
    val missing = wanted.filterNot(header => response.headers.contains(header.headerName))
    if missing.isEmpty then response else response.addHeaders(Headers(missing))

  val middleware: Middleware[Any] = Decorate(directTls = false)

  /** For a listener that terminates TLS itself. */
  val tlsMiddleware: Middleware[Any] = Decorate(directTls = true)

  /** Covers the three ways a response leaves a route: returned, failed with a `Response` in the
    * error channel (`Handler.fail(Response...)`, which `Routes` merges into a response only after
    * this runs), and the answer for a path or method no route matches.
    *
    * The last is a catch-all route rather than `Routes#notFound`: the server installs routes with
    * `oldRoutes ++ newRoutes` (see `NettyDriver#addApp`), which builds a new `Routes` with the
    * default `notFound`, so a customised one never reaches a running server. A wildcard loses to
    * every literal route, so it only answers what nothing else would have. */
  private final class Decorate(directTls: Boolean) extends Middleware[Any]:
    private val fallback: Routes[Any, Response] =
      Routes(Method.ANY / PathCodec.trailing -> handler((_: Path, _: Request) => Response.notFound))

    def apply[Env1 <: Any, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
      Routes.fromIterable(routes.routes ++ fallback.routes.asInstanceOf[Chunk[Route[Env1, Err]]])
        .transform(decorate)

    private def decorate[Env1](
        handler: Handler[Env1, Response, Request, Response],
    ): Handler[Env1, Response, Request, Response] =
      Handler.scoped[Env1]:
        Handler.fromFunctionZIO[Request]: request =>
          handler(request)
            .mapError(SecurityHeaders(request, _, directTls))
            .map(SecurityHeaders(request, _, directTls))
