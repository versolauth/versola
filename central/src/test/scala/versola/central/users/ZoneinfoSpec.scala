package versola.central.users

import zio.test.*

import scala.io.Source
import scala.util.Using

/** The console's selector offers `Intl.supportedValuesOf('timeZone')` of the administrator's browser and central accepts the names of its
  * own JVM's tzdata, two lists that can differ for an alias or a name added since a runtime's tzdata was built. `console-zone-names.txt` is
  * the selector's list as Chromium (the Playwright browser of the console's tests) gives it, with `UTC`; regenerate it from
  * `Intl.supportedValuesOf('timeZone')` when the browser moves, and a name this runtime refuses is then a failing case here instead of a
  * `400` an administrator meets after picking it. */
object ZoneinfoSpec extends ZIOSpecDefault:
  private val offered: List[String] =
    Using.resource(Source.fromResource("console-zone-names.txt"))(_.getLines().map(_.trim).filter(_.nonEmpty).toList)

  def spec = suite("Zoneinfo")(
    test("every name the console's selector offers is accepted") {
      val refused = offered.filterNot(Zoneinfo.isValid)
      assertTrue(offered.size > 400, offered.contains("UTC"), offered.contains("Asia/Almaty"), refused.isEmpty)
    },
    test("an offset, a name in another case, an unknown zone, a blank and an oversize name are refused") {
      val refused = List("+05:00", "UTC+5", "GMT+5", "asia/almaty", "ASIA/ALMATY", "Mars/Olympus", "", " ", " UTC", "UTC ", "A" * 65)
      assertTrue(refused.forall(name => !Zoneinfo.isValid(name)))
    },
    test("the names of the IANA database are accepted as written, aliases included") {
      assertTrue(List("UTC", "Europe/Berlin", "America/St_Johns", "Etc/GMT+5", "Asia/Kolkata").forall(Zoneinfo.isValid))
    },
  )
