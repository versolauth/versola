package versola.oauth.authorize.model

import zio.http.URL

case class RenderableError(error: Error, redirectUri: Option[URL], state: Option[String]):
  def redirectUriWithErrorParams: Option[URL] =
    redirectUri.map { uri =>
      uri.addQueryParams(
        Iterable(
          "error" -> error.error,
          "error_description" -> error.errorDescription,
        )
          ++ error.errorUri.map("error_uri" -> _)
          ++ state.map("state" -> _),
      )
    }
