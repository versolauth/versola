package versola.util

import zio.test.*

object RedirectUriSpec extends ZIOSpecDefault:

  def spec = suite("RedirectUri")(
    suite("validateForRegistration")(
      test("accepts an https redirect URI with a host") {
        assertTrue(
          RedirectUri.validateForRegistration("https://rp.example.com/callback").isRight,
          RedirectUri.validateForRegistration("https://rp.example.com:8443/cb?x=1").isRight,
        )
      },
      test("accepts plain http only to a loopback address (RFC 8252 §7.3)") {
        assertTrue(
          RedirectUri.validateForRegistration("http://localhost:3000/callback").isRight,
          RedirectUri.validateForRegistration("http://127.0.0.1:51004/callback").isRight,
          RedirectUri.validateForRegistration("http://[::1]:51004/callback").isRight,
        )
      },
      test("rejects plain http to a non-loopback host") {
        assertTrue(
          RedirectUri.validateForRegistration("http://rp.example.com/callback").isLeft,
          RedirectUri.validateForRegistration("http://localhost.evil.example/callback").isLeft,
          RedirectUri.validateForRegistration("http://10.0.0.1/callback").isLeft,
        )
      },
      test("rejects private-use URI schemes, which any app on a device can claim") {
        assertTrue(
          RedirectUri.validateForRegistration("com.example.app://callback").isLeft,
          RedirectUri.validateForRegistration("versola://callback").isLeft,
          RedirectUri.validateForRegistration("ftp://rp.example.com/callback").isLeft,
        )
      },
      test("admits a reverse-domain private-use scheme only when the tenant's profile allows it") {
        assertTrue(
          RedirectUri.validateForRegistration("com.example.app://callback", allowPrivateUseSchemes = true).isRight,
          RedirectUri.validateForRegistration("versola://callback", allowPrivateUseSchemes = true).isLeft,
          RedirectUri.validateForRegistration("ftp://rp.example.com/cb", allowPrivateUseSchemes = true).isLeft,
          RedirectUri.validateForRegistration("http://rp.example.com/cb", allowPrivateUseSchemes = true).isLeft,
        )
      },
      // RFC 3986 §3.1 spells a scheme in ASCII. `Char.isLetter` does not, so a scheme written
      // in Cyrillic or fullwidth letters reads as its ASCII lookalike to an operator while
      // being a scheme no platform will ever register.
      test("refuses a reverse-domain scheme that is not spelled in ASCII, or not well-formed") {
        assertTrue(
          !RedirectUri.isPrivateUseScheme("сom.example.app"),
          !RedirectUri.isPrivateUseScheme("com.ex_ample.app"),
          !RedirectUri.isPrivateUseScheme("com."),
          !RedirectUri.isPrivateUseScheme(".com.example"),
          !RedirectUri.isPrivateUseScheme("com..example"),
          RedirectUri.isPrivateUseScheme("com.example.app"),
          RedirectUri.isPrivateUseScheme("io.versola.app-2"),
        )
      },
      test("rejects a malformed, relative or fragment-bearing value") {
        assertTrue(
          RedirectUri.validateForRegistration("not a uri").isLeft,
          RedirectUri.validateForRegistration("/callback").isLeft,
          RedirectUri.validateForRegistration("https://rp.example.com/callback#frag").isLeft,
        )
      },
    ),
    suite("parse")(
      test("still decodes a private-use scheme, so a client stored before the registration rule stays readable") {
        assertTrue(RedirectUri.parse("com.example.app://callback").isRight)
      },
      test("accepts the IPv6 loopback over plain http") {
        assertTrue(RedirectUri.parse("http://[::1]:3000/callback").isRight)
      },
      test("rejects plain http to a non-loopback host") {
        assertTrue(RedirectUri.parse("http://rp.example.com/callback").isLeft)
      },
    ),
  )
