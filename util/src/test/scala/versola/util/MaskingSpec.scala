package versola.util

import zio.test.*

object MaskingSpec extends ZIOSpecDefault:

  def spec = suite("Contact masking")(
    suite("email")(
      test("keeps first and last char of a local part longer than 2 chars") {
        assertTrue(Email.mask(Email("john@example.com")) == "j•••n@example.com")
      },
      test("fully masks a local part of 2 characters") {
        assertTrue(Email.mask(Email("jo@example.com")) == "•••@example.com")
      },
      test("fully masks a local part of 1 character") {
        assertTrue(Email.mask(Email("j@example.com")) == "•••@example.com")
      },
      test("keeps the full domain") {
        assertTrue(Email.mask(Email("someone@sub.example.co.uk")).endsWith("@sub.example.co.uk"))
      },
    ),
    suite("phone")(
      test("keeps the country calling code and last two digits") {
        assertTrue(Phone.mask(Phone("+12025551234")) == "+1 ••• ••• ••34")
      },
      test("masks a different country code the same way") {
        assertTrue(Phone.mask(Phone("+79261234567")) == "+7 ••• ••• •• 67")
      },
      test("keeps a two-digit country calling code") {
        assertTrue(Phone.mask(Phone("+4915123456789")) == "+49 •••• •••••89")
      },
      test("keeps a three-digit country calling code") {
        assertTrue(Phone.mask(Phone("+358401234567")) == "+358 •• •••••67")
      },
      test("fully masks a number too short to have a distinguishable prefix and suffix") {
        assertTrue(Phone.mask(Phone("+123")) == "+•••")
      },
    ),
  )