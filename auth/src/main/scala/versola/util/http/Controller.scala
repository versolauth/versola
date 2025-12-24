package versola.util.http

import versola.oauth.client.model.ClientId
import versola.util.{FormDecoder, Secret}
import zio.{IO, ZIO}
import zio.http.*

trait Controller:
  type Env >: Nothing

  def routes: Routes[Env, Nothing]

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

    def formAs[A: FormDecoder as decoder]: IO[String, A] =
      request.body.asURLEncodedForm.mapError(_.getMessage)
        .flatMap(form => ZIO.fromEither(decoder.decode(form)))

