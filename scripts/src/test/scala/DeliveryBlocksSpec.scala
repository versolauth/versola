import zio.test.*

import scala.util.Try

/** The otp-provider and smtp blocks gen-env.scala writes into auth.conf, and the `--otp-body-<name>` flags
  * versola-cli (`versola configure sms`) hands it. Read as text: a line is "live" when it is not a comment.
  */
object DeliveryBlocksSpec extends ZIOSpecDefault:

  private def live(block: String): List[String] =
    block.linesIterator.map(_.trim).filterNot(line => line.isEmpty || line.startsWith("#")).toList

  def spec = suite("OTP provider and SMTP blocks")(
    suite("otpBodyEntries")(
      test("reads --otp-body-<name> flags, sorted by name") {
        val args = parseCliArgs(Seq("--otp=true", "--otp-url=https://x", "--otp-body-phones={{phone}}", "--otp-body-mes={{message}}=x"))
        assertTrue(otpBodyEntries(args) == List("mes" -> "{{message}}=x", "phones" -> "{{phone}}"))
      },
      test("none without the flags, and a bare prefix is not an entry") {
        assertTrue(
          otpBodyEntries(parseCliArgs(Seq("--otp-url=https://x"))).isEmpty,
          otpBodyEntries(Map("otp-body-" -> "x")).isEmpty,
        )
      },
      test("refuses a name that is not a plain identifier") {
        val bad = List("a-b", "1a", "a.b", "a b", "a\"b").map(name => Try(otpBodyEntries(Map(s"otp-body-$name" -> "v"))).isFailure)
        assertTrue(bad.forall(identity))
      },
    ),
    suite("renderOtpBlock")(
      test("writes the body entries as live lines") {
        val block = renderOtpBlock("https://sms.example.com/send", "POST", "", "", List("mes" -> "{{message}}", "phones" -> "{{phone}}"))
        assertTrue(
          live(block) == List(
            "otp-provider {",
            """method = "POST"""",
            """url = "https://sms.example.com/send"""",
            "body {",
            """mes = "{{message}}"""",
            """phones = "{{phone}}"""",
            "}",
            "}",
          ),
        )
      },
      test("credentials only when set") {
        val none = live(renderOtpBlock("https://x", "POST", "", "", Nil))
        val both = live(renderOtpBlock("https://x", "POST", "login", "pw", Nil))
        assertTrue(
          !none.exists(_.startsWith("username")),
          !none.exists(_.startsWith("password")),
          both.contains("""username = "login""""),
          both.contains("""password = "pw""""),
        )
      },
      test("without body entries the body block holds the commented examples, as before") {
        val block = renderOtpBlock("https://x", "POST", "", "", Nil)
        assertTrue(
          live(block).contains("body {"),
          !live(block).exists(_.startsWith("phones")),
          block.contains("""# phones = "{{phone}}""""),
        )
      },
      test("explains itself") {
        val block = renderOtpBlock("https://x", "POST", "", "", Nil)
        assertTrue(
          block.contains("# SMS one-time codes"),
          block.contains("HTTP basic"),
          block.contains("(the text, code included)"),
        )
      },
    ),
    suite("the blocks that are off")(
      test("otp-provider has no live line, but an example and what to do") {
        assertTrue(
          live(otpBlockOff).isEmpty,
          otpBlockOff.contains("""# otp-provider {"""),
          otpBlockOff.contains("versola configure sms"),
          otpBlockOff.contains("{{phone}}"),
        )
      },
      test("smtp has no live line, but an example and what to do") {
        assertTrue(
          live(smtpBlockOff).isEmpty,
          smtpBlockOff.contains("# smtp {"),
          smtpBlockOff.contains("versola configure smtp"),
        )
      },
    ),
    suite("renderSmtpBlock")(
      test("writes every field") {
        val block = renderSmtpBlock("smtp.example.com", 2525, "login", "pw", "noreply@example.com", "Your code", startTls = false)
        assertTrue(
          live(block) == List(
            "smtp {",
            """host = "smtp.example.com"""",
            "port = 2525",
            """username = "login"""",
            """password = "pw"""",
            """from = "noreply@example.com"""",
            """subject = "Your code"""",
            "start-tls = false",
            "}",
          ),
        )
      },
    ),
  )
