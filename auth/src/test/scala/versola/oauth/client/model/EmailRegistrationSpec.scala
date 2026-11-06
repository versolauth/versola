package versola.oauth.client.model

import versola.util.{Email, UnitSpecBase}
import zio.test.*

object EmailRegistrationSpec extends UnitSpecBase:
  private def setting(
      mode: EmailRegistrationMode,
      domains: String*,
  ) = EmailRegistration(mode, domains.toList, blockSubaddress = false)

  def spec = suite("EmailRegistration.allows")(
    test("a closed policy admits nobody, whatever the list says") {
      assertTrue(
        !setting(EmailRegistrationMode.closed).allows(Email("a@example.com")),
        !setting(EmailRegistrationMode.closed, "example.com").allows(Email("a@example.com")),
      )
    },
    suite("open")(
      test("admits any domain except the listed ones") {
        val policy = setting(EmailRegistrationMode.open, "blocked.com", "*.spam.org")
        assertTrue(
          policy.allows(Email("a@example.com")),
          !policy.allows(Email("a@blocked.com")),
          policy.allows(Email("a@sub.blocked.com")),
          !policy.allows(Email("a@mail.spam.org")),
          policy.allows(Email("a@spam.org")),
        )
      },
    ),
    suite("allowlist")(
      test("admits only the listed domains; a wildcard covers subdomains, not the domain itself") {
        val policy = setting(EmailRegistrationMode.allowlist, "example.com", "*.corp.example.com")
        assertTrue(
          policy.allows(Email("a@example.com")),
          !policy.allows(Email("a@sub.example.com")),
          policy.allows(Email("a@eu.corp.example.com")),
          !policy.allows(Email("a@corp.example.com")),
          !policy.allows(Email("a@other.org")),
          !policy.allows(Email("a@notexample.com")),
        )
      },
      test("an empty list admits nobody") {
        assertTrue(!setting(EmailRegistrationMode.allowlist).allows(Email("a@example.com")))
      },
    ),
    test("compares the domain without regard to case") {
      assertTrue(
        setting(EmailRegistrationMode.allowlist, "example.com").allows(Email("A@Example.COM")),
        !setting(EmailRegistrationMode.open, "blocked.com").allows(Email("a@BLOCKED.com")),
      )
    },
    test("refuses a sub-addressed local part only when asked, in every open mode") {
      val asked = (mode: EmailRegistrationMode) => setting(mode, "example.com").copy(blockSubaddress = true)
      assertTrue(
        !asked(EmailRegistrationMode.allowlist).allows(Email("a+tag@example.com")),
        !asked(EmailRegistrationMode.open).allows(Email("a=b@other.org")),
        !asked(EmailRegistrationMode.open).allows(Email("a#b@other.org")),
        asked(EmailRegistrationMode.open).allows(Email("a.b@other.org")),
        setting(EmailRegistrationMode.allowlist, "example.com").allows(Email("a+tag@example.com")),
      )
    },
    test("an address with several @ is judged by the part after the last one") {
      val policy = setting(EmailRegistrationMode.allowlist, "example.com")
      assertTrue(policy.allows(Email("a@evil.com@example.com")), !policy.allows(Email("a@example.com@evil.com")))
    },
  )
