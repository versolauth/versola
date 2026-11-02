package versola.util.delivery

import versola.util.Email
import zio.http.{Method, URL}

/** What a sender needs to reach an SMTP server. The password is absent for a server that does not ask for one.
  *
  * Both settings types carry a password, so neither prints it: a log line or an error that names the settings must not
  * be the place a secret ends up.
  */
case class SmtpSettings(
    host: String,
    port: Int,
    username: String,
    password: Option[String],
    from: Email,
    subject: String,
    startTls: Boolean,
):
  override def toString: String =
    s"SmtpSettings(host=$host, port=$port, username=$username, password=${password.fold("none")(_ => "***")}, from=$from, startTls=$startTls)"

/** The HTTP service that delivers text messages. `username` and `password` are sent as HTTP Basic when both are present. */
case class SmsProviderSettings(
    method: Method,
    url: URL,
    username: Option[String],
    password: Option[String],
    body: Map[String, String],
):
  override def toString: String =
    s"SmsProviderSettings(method=$method, url=${url.host.getOrElse("")}, username=$username, password=${password.fold("none")(_ => "***")})"
