package versola.central.configuration.challenges

import versola.util.UnitSpecBase
import zio.test.*

object PhoneRegionSpec extends UnitSpecBase:
  private val kz = PhoneRegion("KZ", "+7", 10, "7\\d{9}", true)
  private val ru = PhoneRegion("RU", "+7", 10, "9\\d{9}", true)

  def spec = suite("PhoneRegion.validate")(
    test("accepts an empty list and regions that share a dial code") {
      assertTrue(PhoneRegion.validate(Nil).isEmpty, PhoneRegion.validate(List(kz, ru)).isEmpty)
    },
    test("refuses a region listed twice") {
      assertTrue(PhoneRegion.validate(List(kz, kz.copy(dialCode = "+8"))).exists(_.contains("'KZ' more than once")))
    },
    test("refuses a region that is not two capital letters") {
      assertTrue(PhoneRegion.validate(List(kz.copy(region = "kz"))).isDefined, PhoneRegion.validate(List(kz.copy(region = "KAZ"))).isDefined)
    },
    test("refuses a dial code that is not + and one to four digits") {
      assertTrue(
        PhoneRegion.validate(List(kz.copy(dialCode = "7"))).isDefined,
        PhoneRegion.validate(List(kz.copy(dialCode = "+0"))).isDefined,
        PhoneRegion.validate(List(kz.copy(dialCode = "+12345"))).isDefined,
        PhoneRegion.validate(List(kz.copy(dialCode = "+1242"))).isEmpty,
      )
    },
    test("refuses a length outside 4..14 or one that takes the whole number past 15 digits") {
      assertTrue(
        PhoneRegion.validate(List(kz.copy(length = 3))).isDefined,
        PhoneRegion.validate(List(kz.copy(length = 15))).isDefined,
        PhoneRegion.validate(List(kz.copy(dialCode = "+998", length = 13))).isDefined,
        PhoneRegion.validate(List(kz.copy(dialCode = "+998", length = 12))).isEmpty,
      )
    },
    test("refuses a regex that does not compile") {
      assertTrue(PhoneRegion.validate(List(kz.copy(regex = "(7"))).exists(_.contains("regex of KZ")))
    },
  )
