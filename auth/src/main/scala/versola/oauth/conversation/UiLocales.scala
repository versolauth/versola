package versola.oauth.conversation

import zio.http.{Header, Request, URL}

/** Which locales a page is rendered for.
  *
  * Precedence, most preferred first: the language the user picked in the form being submitted
  * (`ui_locale`), the hints the client or the conversation carry (`ui_locales`),
  * `Accept-Language`, then the tenant default (applied by the renderer when none of these is
  * configured). A choice outlives its request only by being forwarded: the redirect that follows
  * a submit carries it in the URL.
  */
object UiLocales:
  /** A BCP 47 shaped tag. The value comes from the query string, so anything else is ignored rather
    * than matched. */
  private val tag = "[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8}){0,4}".r

  def isValid(locale: String): Boolean = tag.matches(locale)

  /** The locale named by the `ui_locale` query parameter of a form submission, if well-formed. */
  def submitted(request: Request): Option[String] =
    request.url.queryParams.getAll("ui_locale").headOption.filter(isValid)

  /** `url` with the `ui_locale` the next request should render in, if the user picked one. */
  def withLocale(url: URL, locale: Option[String]): URL =
    locale.fold(url)(url.addQueryParam("ui_locale", _))

  /** The locales to try, most preferred first; `hints` are the `ui_locales` the client or the
    * conversation carry. `None` when nothing names a locale at all. */
  def resolve(request: Request, hints: List[String] = Nil): Option[List[String]] =
    Some((submitted(request).toList ++ hints ++ acceptLanguage(request)).distinct).filter(_.nonEmpty)

  /** The `Accept-Language` tags, most preferred first: ordered by quality value (stably, so equal
    * qualities keep the client's order), with `q=0` ("not acceptable") dropped. */
  def acceptLanguage(request: Request): List[String] =
    request.header(Header.AcceptLanguage).toList
      .flatMap(_.renderedValue.split(',').toList)
      .map(_.trim.split(';').toList)
      .flatMap:
        case tag :: params if isValid(tag.trim) => Some(tag.trim -> quality(params))
        case _ => None
      .filter(_._2 > 0)
      .sortBy(-_._2)
      .map(_._1)

  private def quality(params: List[String]): Double =
    params.map(_.trim).collectFirst { case q if q.startsWith("q=") => q.drop(2).toDoubleOption.getOrElse(1.0) }.getOrElse(1.0)

  /** The OIDC `ui_locales` convention: space-separated, most preferred first. */
  def split(raw: String): List[String] =
    raw.split(' ').toList.map(_.trim).filter(isValid)
