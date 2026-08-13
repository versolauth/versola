package versola.util.http

import versola.oauth.client.model.{ClientCredentials, ClientId, ClientIdWithSecret}
import versola.util.Secret
import zio.{IO, ZIO}
import zio.http.{Form, Header, Request}

extension (request: Request)
  /**
   * Extracts client credentials as described in RFC 6749 section 2.3.1.
   *
   * `client_secret_basic` takes precedence, `client_secret_post` is read from the
   * already parsed request form, since the request body can be consumed only once.
   * A client must not use more than one authentication method in a single request.
   */
  def extractCredentials(form: Form): IO[Option[Nothing], ClientCredentials] =
    ZIO.fromOption:
      (request.header(Header.Authorization), postCredentials(form)) match
        case (Some(Header.Authorization.Basic(username, password)), postCredentials)
            if postCredentials.forall(_.exists(_.clientSecret.isEmpty)) =>
          val (secret, clientId) = (password.stringValue, ClientId(username))
          // `client_id` alone is not an authentication method, but when it accompanies Basic
          // (as RFC 9126 §2.1 requires at /par) it must identify the authenticated client.
          if postCredentials.exists(_.exists(_.clientId != clientId)) then
            None
          else if secret.isEmpty then
            Some(ClientIdWithSecret(clientId, None))
          else
            Secret.fromBase64Url(secret).toOption
              .map(secret => ClientIdWithSecret(clientId, Some(secret)))
        case (None, Some(credentials)) =>
          credentials
        case _ =>
          None

private def postCredentials(form: Form): Option[Option[ClientIdWithSecret]] =
  def field(name: String) = form.get(name).flatMap(_.stringValue).filter(_.nonEmpty)

  (field("client_id"), field("client_secret")) match
    case (Some(clientId), None) =>
      Some(Some(ClientIdWithSecret(ClientId(clientId), None)))
    case (Some(clientId), Some(secret)) =>
      Some:
        Secret.fromBase64Url(secret).toOption
          .map(secret => ClientIdWithSecret(ClientId(clientId), Some(secret)))
    case (None, Some(_)) =>
      Some(None)
    case (None, None) =>
      None
