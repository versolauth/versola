package versola.loadgen.protocol

import zio.test.*

object ConversationStepSpec extends ZIOSpecDefault:

  def spec = suite("ConversationStep")(
    test("extracts a known step from the meta tag") {
      val html = """<html><head><meta name="versola-step" content="otp"></head></html>"""
      assertTrue(ConversationStep.fromHtml(html) == Some(ConversationStep.Otp))
    },
    test("wraps an unrecognized step value in Unknown rather than failing") {
      val html = """<meta name="versola-step" content="future-step">"""
      assertTrue(ConversationStep.fromHtml(html) == Some(ConversationStep.Unknown("future-step")))
    },
    test("returns None when the page carries no versola-step meta tag") {
      assertTrue(ConversationStep.fromHtml("<html></html>") == None)
    },
  )
