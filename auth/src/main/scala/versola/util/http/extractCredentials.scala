package versola.util.http

import versola.oauth.client.model.{ClientCredentials, ClientId, ClientIdWithAssertion, ClientIdWithSecret}
import versola.util.{ClientAssertion, Secret}
import zio.{IO, ZIO}
import zio.http.{Form, Header, Request}

extension (request: Request)
  /**
   * Extracts client credentials as described in RFC 6749 section 2.3.1.
   *
   * `client_secret_basic` takes precedence, `client_secret_post` is read from the
   * already parsed request form, since the request body can be consumed only once.
   * A client must not use more than one authentication method in a single request.
   *
   * RFC 7523 §2.2 `private_key_jwt` arrives in the same form, as `client_assertion_type` and
   * `client_assertion`, and is checked for first: it is the whole credential, so a request
   * carrying one has no reason to also carry a secret, and one that does is using two methods
   * at once.
   */
  def extractCredentials(form: Form): IO[Option[Nothing], ClientCredentials] =
    ZIO.fromOption:
      assertionCredentials(form) match
        case Some(credentials) =>
          // §2.3 again: an assertion beside Basic is two methods, whichever the server would
          // otherwise have picked.
          credentials.filter(_ => request.header(Header.Authorization).isEmpty)
        case None =>
          basicOrPostCredentials(form)

  private def basicOrPostCredentials(form: Form): Option[ClientCredentials] =
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

/** Reads an RFC 7523 client assertion off the form.
  *
  * `None` means the request did not attempt this method at all and the secret-based ones
  * still apply; `Some(None)` means it attempted it and got it wrong, which is a failure
  * rather than a fallback -- a client that sent an assertion asked to be authenticated by it,
  * and quietly authenticating it some weaker way instead is the downgrade the method exists
  * to rule out.
  */
private def assertionCredentials(form: Form): Option[Option[ClientCredentials]] =
  def field(name: String) = form.get(name).flatMap(_.stringValue).filter(_.nonEmpty)

  (field("client_assertion_type"), field("client_assertion")) match
    case (None, None) =>
      None
    case (Some(assertionType), Some(assertion)) if assertionType == ClientAssertion.Type =>
      Some:
        ClientAssertion.subject(assertion).map(ClientId(_)).filter: clientId =>
          // A `client_id` sent beside the assertion must be the client the assertion names;
          // a secret sent beside it is a second authentication method.
          field("client_secret").isEmpty && field("client_id").forall(_ == clientId)
        .map(ClientIdWithAssertion(_, assertion))
    case _ =>
      // Half the pair, or a `client_assertion_type` naming a mechanism this server does not
      // implement: either way the client asked for an authentication method it cannot get.
      Some(None)

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
