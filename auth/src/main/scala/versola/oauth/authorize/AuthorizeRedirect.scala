package versola.oauth.authorize

import versola.oauth.authorize.model.ResponseMode
import versola.oauth.model.State
import versola.util.encodeQueryParam
import zio.http.URL

object AuthorizeRedirect:
  /** The single parameter a JARM response is carried in (JARM §4.2). */
  val JwtParameter = "response"

  /** The parameters of a successful authorization response, in the order they are placed on
    * the redirect URI. Under JARM these become the claims of the `response` JWT instead.
    */
  def successParams(code: String, state: Option[State], idToken: Option[String], iss: String): List[(String, String)] =
    List("code" -> code, "iss" -> iss) ++
      idToken.map("id_token" -> _) ++
      state.map("state" -> _)

  /** Places response parameters on the redirect URI.
    *
    * The query string and the fragment are the two placements OAuth defines; which one is
    * used is the response mode's decision, not this function's -- OIDC Core §3.3 mandates
    * the fragment for a response type that returns an `id_token` from the authorization
    * endpoint, and RFC 6749 §4.1.2 the query for the plain code flow, and
    * [[ResponseMode.parse]] has already held the request to that.
    */
  def responseUrl(redirectUri: URL, params: List[(String, String)], mode: ResponseMode): URL =
    if mode.useFragment then
      val raw = params.map((k, v) => s"$k=${encodeQueryParam(v)}").mkString("&")
      URL.decode(s"${redirectUri.encode}#$raw").getOrElse(redirectUri)
    else
      redirectUri.addQueryParams(params)
