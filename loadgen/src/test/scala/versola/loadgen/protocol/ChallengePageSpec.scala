package versola.loadgen.protocol

import zio.test.*

object ChallengePageSpec extends ZIOSpecDefault:

  private val conversation = ConversationCookie("conversation-1")

  private def page(step: String, csrf: String): String =
    """<!DOCTYPE html><html><head><meta name="versola-step" content="""" + step + """">""" +
      """<script>window.__VERSOLA_FORM__ = {"step":{"_type":"Otp"},"csrf":"""" + csrf +
      """","title":"Sign in"};</script></head><body></body></html>"""

  def spec = suite("ChallengePage.parse")(
    test("extracts the step and the csrf token from a rendered form") {
      val parsed = ChallengePage.parse(conversation, page("otp", "csrf-abc123"))
      assertTrue(parsed.step == Some(ConversationStep.Otp), parsed.csrf == Some(Csrf("csrf-abc123")))
    },
    test("tolerates whitespace around the JSON separator") {
      val parsed = ChallengePage.parse(conversation, """<meta name="versola-step" content="credential"><script>{"csrf"  :   "spaced"}</script>""")
      assertTrue(parsed.csrf == Some(Csrf("spaced")))
    },
    test("takes the first csrf occurrence, so the inlined script bundle below it is never reached") {
      val parsed = ChallengePage.parse(conversation, page("otp", "first") + """<script>var x = {"csrf":"second"};</script>""")
      assertTrue(parsed.csrf == Some(Csrf("first")))
    },
    test("reports a page carrying neither rather than throwing, unlike the e2e original") {
      val parsed = ChallengePage.parse(conversation, "<html><body>Something went wrong</body></html>")
      assertTrue(parsed.step == None, parsed.csrf == None, parsed.conversation == conversation)
    },
    test("an unknown step value stays readable instead of failing the parse") {
      val parsed = ChallengePage.parse(conversation, page("conversation-expired", "csrf-1"))
      assertTrue(parsed.step == Some(ConversationStep.Unknown("conversation-expired")))
    },
  )
