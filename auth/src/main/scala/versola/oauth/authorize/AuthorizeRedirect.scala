package versola.oauth.authorize

import versola.oauth.model.State
import zio.http.URL

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object AuthorizeRedirect:
  /** Builds the authorization response redirect URI.
    *
    * When an `id_token` is returned from the authorization endpoint (Hybrid Flow,
    * `response_type=code id_token`) OIDC Core §3.3 mandates the fragment response mode,
    * so all parameters are placed in the URL fragment. For the plain authorization-code
    * flow the parameters are placed in the query string.
    */
  def responseUrl(redirectUri: URL, code: String, state: Option[State], idToken: Option[String]): URL =
    val params: List[(String, String)] =
      List("code" -> code) ++
        idToken.map("id_token" -> _) ++
        state.map("state" -> _)
    idToken match
      case None =>
        redirectUri.addQueryParams(params)
      case Some(_) =>
        val raw = params.map((k, v) => s"$k=${enc(v)}").mkString("&")
        URL.decode(s"${redirectUri.encode}#$raw").getOrElse(redirectUri)

  private def enc(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
