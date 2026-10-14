package versola.oauth.client.model

import versola.util.UnitSpecBase
import zio.test.*

object PhoneRegionSpec extends UnitSpecBase:
  private val kz = PhoneRegion("KZ", "+7", 10, "7\\d{9}")
  private val ru = PhoneRegion("RU", "+7", 10, "9\\d{9}")
  private val us = PhoneRegion("US", "+1", 10, "\\d{10}")
  private val bs = PhoneRegion("BS", "+1242", 7, "\\d{7}")

  def spec = suite("PhoneRegion")(
    suite("allows")(
      test("takes any number when the tenant lists no regions") {
        assertTrue(PhoneRegion.allows(Nil, "+4915112345678"))
      },
      test("with KZ and RU sharing +7 takes a number that starts with 7 and one that starts with 9") {
        val regions = List(kz, ru)
        assertTrue(
          PhoneRegion.allows(regions, "+77011234567"),
          PhoneRegion.allows(regions, "+79011234567"),
          !PhoneRegion.allows(regions, "+71011234567"),
        )
      },
      test("with only RU takes 9 and refuses 7") {
        assertTrue(PhoneRegion.allows(List(ru), "+79011234567"), !PhoneRegion.allows(List(ru), "+77011234567"))
      },
      test("with only KZ takes 7 and refuses 9") {
        assertTrue(PhoneRegion.allows(List(kz), "+77011234567"), !PhoneRegion.allows(List(kz), "+79011234567"))
      },
      test("refuses a number of the wrong length even when the regex would match its prefix") {
        assertTrue(!PhoneRegion.allows(List(kz), "+7701123456"), !PhoneRegion.allows(List(kz), "+770112345678"))
      },
      test("refuses a dial code the tenant did not list") {
        assertTrue(!PhoneRegion.allows(List(kz), "+998901234567"))
      },
      test("refuses a number whose national part is not all digits") {
        assertTrue(!PhoneRegion.allows(List(kz), "+7701123456a"))
      },
    ),
    suite("resolve")(
      test("names the region whose regex fits when two share a dial code") {
        assertTrue(
          PhoneRegion.resolve(List(kz, ru), "+77011234567").map(_.region) == Some("KZ"),
          PhoneRegion.resolve(List(kz, ru), "+79011234567").map(_.region) == Some("RU"),
        )
      },
      test("prefers the earlier region when both fit") {
        val anyKz = kz.copy(regex = "\\d{10}")
        val anyRu = ru.copy(regex = "\\d{10}")
        assertTrue(
          PhoneRegion.resolve(List(anyRu, anyKz), "+77011234567").map(_.region) == Some("RU"),
          PhoneRegion.resolve(List(anyKz, anyRu), "+77011234567").map(_.region) == Some("KZ"),
        )
      },
      test("prefers the longer dial code when one starts the other") {
        assertTrue(
          PhoneRegion.resolve(List(us, bs), "+12425551234").map(_.region) == Some("BS"),
          PhoneRegion.resolve(List(us, bs), "+12025551234").map(_.region) == Some("US"),
        )
      },
      test("is empty when nothing fits") {
        assertTrue(PhoneRegion.resolve(List(kz), "+79011234567").isEmpty)
      },
    ),
  )
