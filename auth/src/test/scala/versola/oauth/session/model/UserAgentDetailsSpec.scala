package versola.oauth.session.model

import versola.util.UnitSpecBase
import zio.test.*

object UserAgentDetailsSpec extends UnitSpecBase:

  case class TestCase(
      description: String,
      input: Option[String],
      expected: UserAgentDetails,
  )

  def testCase(tc: TestCase) = test(tc.description):
    assertTrue(UserAgentDetails.parse(tc.input) == tc.expected)

  def ua(info: UserAgentDetails)(description: String, input: String) =
    TestCase(description, Some(input), info)

  val spec = suite("UserAgentDetails")(
    suite("parse")(
      suite("platform")(
        List(
          TestCase("unknown for None", None, UserAgentDetails(None, None, None, None)),
          TestCase("unknown for empty string", Some(""), UserAgentDetails(None, None, None, None)),
          TestCase("unknown for whitespace", Some("   "), UserAgentDetails(None, None, None, None)),
          TestCase(
            "ios for iPhone UA",
            Some("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"),
            UserAgentDetails(Some("ios"), Some("iOS 17.0"), Some("Safari"), Some("17")),
          ),
          TestCase(
            "android for Android UA",
            Some("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"),
            UserAgentDetails(Some("android"), Some("Android 13"), Some("Chrome"), Some("114")),
          ),
          TestCase(
            "desktop for Windows UA",
            Some("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"),
            UserAgentDetails(Some("desktop"), Some("Windows 10 / 11"), Some("Chrome"), Some("125")),
          ),
          TestCase(
            "desktop for macOS UA",
            Some("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"),
            UserAgentDetails(Some("desktop"), Some("macOS 14.5"), Some("Safari"), Some("17")),
          ),
        ).map(testCase)
      ),
      suite("OS detection")(
        List(
          TestCase(
            "Windows 10/11 for NT 10.0",
            Some("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/125.0.0.0"),
            UserAgentDetails(Some("desktop"), Some("Windows 10 / 11"), Some("Chrome"), Some("125")),
          ),
          TestCase(
            "Windows 7 for NT 6.1",
            Some("Mozilla/5.0 (Windows NT 6.1; Win64; x64) Chrome/100.0.0.0"),
            UserAgentDetails(Some("desktop"), Some("Windows 7"), Some("Chrome"), Some("100")),
          ),
          TestCase(
            "macOS with dots",
            Some("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) Version/17.5 Safari/605.1.15"),
            UserAgentDetails(Some("desktop"), Some("macOS 14.5"), Some("Safari"), Some("17")),
          ),
          TestCase(
            "ChromeOS",
            Some("Mozilla/5.0 (X11; CrOS x86_64 14.0) AppleWebKit/537.36 Chrome/100.0.0.0"),
            UserAgentDetails(Some("desktop"), Some("ChromeOS"), Some("Chrome"), Some("100")),
          ),
          TestCase(
            "Android version",
            Some("Mozilla/5.0 (Linux; Android 13; Pixel 7) Chrome/114.0.0.0"),
            UserAgentDetails(Some("android"), Some("Android 13"), Some("Chrome"), Some("114")),
          ),
          TestCase(
            "iOS version",
            Some("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/604.1"),
            UserAgentDetails(Some("ios"), Some("iOS 17.0"), Some("Safari"), None),
          ),
          TestCase(
            "Linux",
            Some("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Firefox/115.0"),
            UserAgentDetails(Some("desktop"), Some("Linux"), Some("Firefox"), Some("115")),
          ),
        ).map(testCase)
      ),
      suite("browser detection")(
        List(
          TestCase(
            "Edge",
            Some("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0"),
            UserAgentDetails(Some("desktop"), Some("Windows 10 / 11"), Some("Edge"), Some("125")),
          ),
          TestCase(
            "Firefox",
            Some("Mozilla/5.0 (X11; Linux x86_64; rv:115.0) Gecko/20100101 Firefox/115.0"),
            UserAgentDetails(Some("desktop"), Some("Linux"), Some("Firefox"), Some("115")),
          ),
          TestCase(
            "Chrome",
            Some("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"),
            UserAgentDetails(Some("desktop"), Some("macOS 10.15.7"), Some("Chrome"), Some("124")),
          ),
          TestCase(
            "Safari with Version token",
            Some("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Version/17.5 Safari/605.1.15"),
            UserAgentDetails(Some("desktop"), Some("macOS 14.5"), Some("Safari"), Some("17")),
          ),
          TestCase(
            "Safari without Version token",
            Some("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1"),
            UserAgentDetails(Some("ios"), Some("iOS 17.0"), Some("Safari"), None),
          ),
          TestCase(
            "unknown browser",
            Some("CustomBot/1.0"),
            UserAgentDetails(None, None, None, None),
          ),
        ).map(testCase)
      ),
    )
  )
