package versola.edge.login

import zio.http.URL

/** Where a login sends the browser once it completes, instead of the preset's fixed post-login page: the
  * page that asked for the sign-in, so the user returns to what they were doing.
  *
  * Only a path on the preset's own origin is accepted. The value is a path and a query as the browser sent
  * them, and it is attached to the host of the preset's `postLoginRedirectUri` ([[onto]]), so no value can
  * name another host: the scheme, host and port are never read from it.
  */
object ReturnTo:
  private val maxLength = 2048

  /** `Some(value)` when `raw` is a path of this origin and nothing else: it starts with a single `/`, has no
    * backslash (a browser reads `/\evil.example` as `//evil.example`), no control character and no
    * fragment, and decodes as a relative URL. */
  def parse(raw: String): Option[String] =
    Option(raw)
      .filter(value => value.length <= maxLength && value.startsWith("/") && !value.startsWith("//"))
      .filter(value => !value.contains('\\') && !value.contains('#') && !value.exists(char => char < ' ' || char == '\u007f'))
      .filter(value => URL.decode(value).toOption.exists(url => !url.isAbsolute))

  /** `base` with the path and query of `returnTo` in place of its own; `base` when `returnTo` is absent or not
    * a path of this origin. */
  def onto(base: URL, returnTo: Option[String]): URL =
    returnTo.flatMap(parse).flatMap(value => URL.decode(value).toOption).fold(base): relative =>
      base.copy(path = relative.path, queryParams = relative.queryParams, fragment = None)
