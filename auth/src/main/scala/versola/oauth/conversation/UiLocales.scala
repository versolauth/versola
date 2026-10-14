package versola.oauth.conversation

import zio.Duration
import zio.http.{Cookie, Header, Path, Request, Response}

/** Which locales a page is rendered for, for the pages the user reaches with no other
  * source for the choice (an expired conversation, logout, account settings).
  *
  * Precedence, most preferred first: what the request names explicitly, the conversation
  * record, the `ui_locale` cookie, `Accept-Language`, then the tenant default (applied by the
  * renderer when none of these is configured). The cookie only remembers a choice the user made
  * in a form; it carries no identity and is read only after it is matched against the locales
  * the tenant has configured.
  */
object UiLocales:
  val cookieName = "ui_locale"

  private val cookieTtl = Duration.fromSeconds(31536000L)

  /** A BCP 47 shaped tag. The value comes from the query string and from a cookie the user
    * controls, so anything else is ignored rather than stored or matched. */
  private val tag = "[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8}){0,4}".r

  def isValid(locale: String): Boolean = tag.matches(locale)

  def cookie(locale: String): Cookie.Response =
    Cookie.Response(
      name = cookieName,
      content = locale,
      domain = None,
      path = Some(Path.root),
      isSecure = true,
      isHttpOnly = false, // the forms set it too when the user changes language on a page without a request of its own
      maxAge = Some(cookieTtl),
      sameSite = Some(Cookie.SameSite.Lax),
    )

  /** The locale named by the `ui_locale` query parameter of a form submission, if well-formed. */
  def submitted(request: Request): Option[String] =
    request.url.queryParams.getAll("ui_locale").headOption.filter(isValid)

  /** Remembers the locale a form was submitted with, so pages that follow without a
    * conversation to read it from keep it. */
  def remember(request: Request)(response: Response): Response =
    submitted(request).fold(response)(locale => response.addCookie(cookie(locale)))

  /** `explicit` and `fromRecord` are tried first, in that order, then the cookie, then
    * `Accept-Language`. `None` when the request names no locale at all. */
  def resolve(
      request: Request,
      explicit: List[String] = Nil,
      fromRecord: List[String] = Nil,
  ): Option[List[String]] =
    val remembered = request.cookie(cookieName).map(_.content).filter(isValid).toList
    Some((explicit ++ fromRecord ++ remembered ++ acceptLanguage(request)).distinct).filter(_.nonEmpty)

  /** The `Accept-Language` tags in the order the client listed them, quality values dropped. */
  def acceptLanguage(request: Request): List[String] =
    request.header(Header.AcceptLanguage).toList
      .flatMap(_.renderedValue.split(',').toList)
      .map(_.takeWhile(_ != ';').trim)
      .filter(isValid)

  /** The OIDC `ui_locales` convention: space-separated, most preferred first. */
  def split(raw: String): List[String] =
    raw.split(' ').toList.map(_.trim).filter(isValid)
