package versola.oauth.authorize

import versola.oauth.model.State
import versola.util.encodeQueryParam
import zio.http.URL

object AuthorizeRedirect:
  /** Builds the authorization response redirect URI.
    *
    * When an `id_token` is returned from the authorization endpoint (Hybrid Flow,
    * `response_type=code id_token`) OIDC Core §3.3 mandates the fragment response mode,
    * so all parameters are placed in the URL fragment. For the plain authorization-code
    * flow the parameters are placed in the query string.
    */
  def responseUrl(redirectUri: URL, code: String, state: Option[State], idToken: Option[String], iss: String): URL =
    val params: List[(String, String)] =
      List("code" -> code, "iss" -> iss) ++
        idToken.map("id_token" -> _) ++
        state.map("state" -> _)
    idToken match
      case None =>
        redirectUri.addQueryParams(params)
      case Some(_) =>
        val raw = params.map((k, v) => s"$k=${encodeQueryParam(v)}").mkString("&")
        URL.decode(s"${redirectUri.encode}#$raw").getOrElse(redirectUri)
