package versola.oauth.client.model

/** Throwaway-mailbox providers, for a tenant that asks to refuse them (`blockDisposable`). A short
  * seed of the most used ones, not a complete or current list: new providers appear faster than
  * a list in code can follow, so this lowers the noise and is no substitute for verifying the
  * address with a one-time code, which registration always does.
  *
  * A domain is refused when it, or any parent of it, is listed (`a.mailinator.com`).
  */
object DisposableEmailDomains:
  private val domains: Set[String] = Set(
    "mailinator.com",
    "guerrillamail.com",
    "guerrillamail.net",
    "guerrillamail.org",
    "guerrillamailblock.com",
    "sharklasers.com",
    "grr.la",
    "10minutemail.com",
    "10minutemail.net",
    "20minutemail.com",
    "tempmail.com",
    "temp-mail.org",
    "temp-mail.io",
    "tempmail.net",
    "tempail.com",
    "tempr.email",
    "yopmail.com",
    "yopmail.net",
    "yopmail.fr",
    "trashmail.com",
    "trashmail.net",
    "trashmail.de",
    "getnada.com",
    "nada.email",
    "throwawaymail.com",
    "dispostable.com",
    "maildrop.cc",
    "mailnesia.com",
    "fakeinbox.com",
    "fakemail.net",
    "mohmal.com",
    "emailondeck.com",
    "moakt.com",
    "burnermail.io",
    "mintemail.com",
    "mailcatch.com",
    "mailnull.com",
    "spamgourmet.com",
    "spam4.me",
    "getairmail.com",
    "dropmail.me",
    "mytemp.email",
    "tmpmail.org",
    "tmpmail.net",
    "mail.tm",
    "inboxkitten.com",
    "discard.email",
    "mailsac.com",
    "harakirimail.com",
    "33mail.com",
    "anonbox.net",
    "jetable.org",
    "spambox.us",
    "spamex.com",
    "mailforspam.com",
    "easytrashmail.com",
    "wegwerfmail.de",
    "einrot.com",
    "cuvox.de",
    "armyspy.com",
    "dayrep.com",
    "fleckens.hu",
    "gustr.com",
    "jourrapide.com",
    "rhyta.com",
    "superrito.com",
    "teleworm.us",
  )

  def contains(domain: String): Boolean =
    LazyList.iterate(Option(domain))(_.flatMap(parent)).takeWhile(_.isDefined).flatten.exists(domains.contains)

  private def parent(domain: String): Option[String] =
    Some(domain.indexOf('.')).filter(_ >= 0).map(dot => domain.substring(dot + 1)).filter(_.contains('.'))
