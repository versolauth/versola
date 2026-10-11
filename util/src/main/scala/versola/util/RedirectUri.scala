package versola.util

import zio.http.{Scheme, URL}
import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

/**
 * OAuth 2.1 compliant redirect URI
 *
 * Two levels of check, deliberately kept apart:
 *
 *  - [[parse]] is the *structural* check every decoded value goes through (RFC 6749 §3.1.2):
 *    absolute, no fragment, and no plain HTTP to a non-loopback host. It still admits a
 *    private-use scheme, because it also decodes values that are already stored -- a client
 *    registered before [[validateForRegistration]] existed has to stay readable (and
 *    removable) rather than failing every decode it appears in.
 *  - [[validateForRegistration]] is the *policy* a newly registered value is held to: FAPI 2.0
 *    Security Profile §5.3.2.2 -- HTTPS, or HTTP to a loopback address only (RFC 8252 §7.3).
 */
type RedirectUri = RedirectUri.Type

object RedirectUri:
  opaque type Type <: String = String

  inline def apply(uri: String): RedirectUri = uri

  /** RFC 8252 §7.3 / §8.3: the loopback interface, the only place plain HTTP is tolerated. The
    * IPv6 literal may reach here with or without its brackets depending on how the URL was
    * decoded, so both spellings are listed.
    */
  private val LoopbackHosts = Set("localhost", "127.0.0.1", "[::1]", "::1")

  def isLoopback(host: String): Boolean = LoopbackHosts.contains(host.toLowerCase)

  def parse(uri: String): Either[String, RedirectUri] =
    URL.decode(uri) match
      case Left(_) =>
        Left(s"Invalid URI format: $uri")
      case Right(url) if !url.isAbsolute =>
        Left("Redirect URI must be absolute")
      case Right(url) if url.fragment.isDefined =>
        Left("Redirect URI must not contain fragment (#)")
      case Right(url) =>
        validateScheme(url, uri)

  private def validateScheme(url: URL, originalUri: String): Either[String, RedirectUri] =
    url.scheme match
      case Some(scheme) if scheme == Scheme.HTTP =>
        url.host match
          case Some(host) if isLoopback(host) =>
            Right(RedirectUri(originalUri))
          case _ =>
            Left("HTTP scheme only allowed for localhost, 127.0.0.1 or [::1]")

      case Some(_) =>
        Right(RedirectUri(originalUri))

      case None =>
        Left("Redirect URI must have a scheme")

  /** The rule a redirect URI is held to when a client registers it (or a patch adds it).
    *
    * FAPI 2.0 Security Profile §5.3.2.2-8 (and RFC 9700 §2.1 / §4.1.1): only `https` with a
    * host, or `http` to a loopback address for a native app's local listener (RFC 8252 §7.3).
    *
    * Private-use URI schemes (`com.example.app:/callback`, RFC 8252 §7.1) are refused by
    * default: any app on the device can claim one, so a custom-scheme redirect cannot
    * authenticate where the authorization code lands, and FAPI 2.0 names no allowance for
    * them. Native clients use claimed `https` redirects (Android App Links / iOS Universal
    * Links, RFC 8252 §7.2), which this rule already admits.
    *
    * @param allowPrivateUseSchemes set for a tenant outside FAPI 2.0, where plain RFC 8252
    *   applies. Even then only a reverse-domain scheme (§7.1: "based on a domain name ...
    *   expressed in reverse order", so it contains a period) is admitted -- which is also what
    *   keeps `javascript:`, `data:` or `file:` out.
    */
  def validateForRegistration(uri: String, allowPrivateUseSchemes: Boolean = false): Either[String, RedirectUri] =
    parse(uri).flatMap: redirectUri =>
      // `parse` already proved the value decodes into an absolute URL.
      val url = redirectUri.toUrl
      url.scheme match
        case Some(Scheme.HTTPS) if url.host.exists(_.nonEmpty) =>
          Right(redirectUri)
        case Some(Scheme.HTTP) if url.host.exists(isLoopback) =>
          Right(redirectUri)
        case Some(Scheme.HTTPS) =>
          Left("Redirect URI must name a host")
        case Some(scheme) if isPrivateUseScheme(scheme.encode) =>
          if allowPrivateUseSchemes then Right(redirectUri)
          else
            Left(
              "Redirect URI must use https:// (or http:// to a loopback address); private-use schemes are not accepted under the FAPI 2.0 security profile",
            )
        case _ =>
          Left("Redirect URI must use https:// (or http:// to a loopback address)")

  /** RFC 8252 §7.1: a reverse-domain-name scheme, which is the only private-use scheme shape
    * the RFC lets a native app pick.
    *
    * Held to RFC 3986 §3.1's own alphabet as well as to §7.1's shape: a scheme is
    * `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )`, all ASCII. `Char.isLetter` is
    * Unicode-aware, so without this a scheme spelled with Cyrillic or fullwidth letters --
    * one that no platform will ever register, and that reads as its ASCII lookalike -- would
    * pass for a reverse domain name. `split('.')` also drops a trailing empty label, so the
    * label count is checked against the separator count rather than taken from it.
    */
  def isPrivateUseScheme(scheme: String): Boolean =
    val lower = scheme.toLowerCase
    val labels = lower.split('.')
    val everyLabelKept = labels.length == lower.count(_ == '.') + 1
    val reverseDomainName = labels.length > 1 && everyLabelKept && labels.forall(isSchemeLabel)
    lower != "http" && lower != "https" && reverseDomainName

  /** One label of a scheme, held to RFC 3986 §3.1: `ALPHA *( ALPHA / DIGIT / "+" / "-" )`,
    * the period that separates labels having been split on already. */
  private def isSchemeLabel(label: String): Boolean =
    label.nonEmpty && isAsciiLetter(label.head) && label.forall(isSchemeChar)

  private def isAsciiLetter(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')

  private def isSchemeChar(c: Char): Boolean =
    isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '+' || c == '-'

  given Schema[Type] = Schema.primitive[String]
    .transformOrFail(parse, Right(_))

  given JsonEncoder[Type] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[Type] = JsonDecoder.string.mapOrFail(parse)

  /** Every [[RedirectUri]] is constructed via [[parse]], which already proved it
   *  decodes into an absolute [[URL]], so this conversion is total in practice.
   */
  extension (uri: Type)
    def toUrl: URL = URL.decode(uri)
      .getOrElse(throw IllegalStateException(s"Invalid RedirectUri '$uri'"))
