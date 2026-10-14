package versola.central.configuration.challenges

import versola.util.UnitSpecBase
import zio.test.*

object EmailRegistrationSpec extends UnitSpecBase:
  private def settingWith(domains: String*) =
    EmailRegistration(EmailRegistrationMode.allowlist, domains.toList, blockDisposable = true, blockSubaddress = false)

  def spec = suite("EmailRegistration")(
    suite("normalizeDomain")(
      test("lower-cases, trims and keeps a wildcard") {
        assertTrue(
          EmailRegistration.normalizeDomain(" Example.COM ") == Some("example.com"),
          EmailRegistration.normalizeDomain("*.Example.com") == Some("*.example.com"),
        )
      },
      test("turns an IDN into punycode") {
        assertTrue(
          EmailRegistration.normalizeDomain("пример.рф") == Some("xn--e1afmkfd.xn--p1ai"),
          EmailRegistration.normalizeDomain("*.пример.рф") == Some("*.xn--e1afmkfd.xn--p1ai"),
        )
      },
      test("refuses what is not a domain") {
        assertTrue(
          EmailRegistration.normalizeDomain("") == None,
          EmailRegistration.normalizeDomain("localhost") == None,
          EmailRegistration.normalizeDomain("*.com") == None,
          EmailRegistration.normalizeDomain("a@example.com") == None,
          EmailRegistration.normalizeDomain("*example.com") == None,
          EmailRegistration.normalizeDomain("*.*.example.com") == None,
          EmailRegistration.normalizeDomain("-a.example.com") == None,
          EmailRegistration.normalizeDomain("example..com") == None,
          EmailRegistration.normalizeDomain("exa mple.com") == None,
          EmailRegistration.normalizeDomain(("a" * 64) + ".com") == None,
        )
      },
    ),
    suite("normalize")(
      test("normalizes every entry and keeps the mode and flags") {
        assertTrue(EmailRegistration.normalize(settingWith("A.com", "*.B.com")) == Right(settingWith("a.com", "*.b.com")))
      },
      test("refuses a duplicate once lower-cased") {
        assertTrue(EmailRegistration.normalize(settingWith("a.com", "A.COM")).left.exists(_.contains("'a.com' more than once")))
      },
      test("refuses more than the maximum number of domains") {
        val tooMany = (0 to EmailRegistration.MaxDomains).map(i => s"d$i.com")
        assertTrue(EmailRegistration.normalize(settingWith(tooMany*)).isLeft)
      },
      test("names the first bad entry") {
        assertTrue(EmailRegistration.normalize(settingWith("ok.com", "bad domain")).left.exists(_.contains("'bad domain'")))
      },
    ),
  )
