package versola.util.http

import versola.oauth.client.model.{ClientCredentials, ClientId, ClientIdWithSecret}
import versola.util.Secret
import zio.{IO, ZIO}
import zio.http.{Header, Request}

extension (request: Request)
  def extractCredentials: IO[Option[Nothing], ClientCredentials] =
    ZIO.fromOption:
      request.header(Header.Authorization) match
        case Some(Header.Authorization.Basic(username, password)) =>
          val (secret, clientId) = (password.stringValue, ClientId(username))
          if secret.isEmpty then
            Some(ClientIdWithSecret(clientId, None))
          else
            Secret.fromBase64Url(secret).toOption
              .map(secret => ClientIdWithSecret(clientId, Some(secret)))
        case _ =>
          None
