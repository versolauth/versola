package versola.util.http

import zio.test.*

object EntityVersionSpec extends ZIOSpecDefault:

  def spec = suite("EntityVersion")(
    test("a version is a quoted strong tag, and parses back, quoted or not") {
      assertTrue(
        EntityVersion.etag(3) == "\"3\"",
        EntityVersion.parseIfMatch("\"3\"") == Right(3L),
        EntityVersion.parseIfMatch("3") == Right(3L),
        EntityVersion.parseIfMatch("  \"0\" ") == Right(0L),
      )
    },
    test("a weak tag, a list, a wildcard, a negative number and anything else is not a version") {
      val refused = List("W/\"3\"", "\"3\", \"4\"", "*", "\"-1\"", "\"abc\"", "", "\"\"", "\"3")
      assertTrue(refused.forall(EntityVersion.parseIfMatch(_).isLeft))
    },
    test("the version of a change is the header's or the body's; both given must agree, neither is None") {
      assertTrue(
        EntityVersion.expected(Some("\"3\""), None) == Right(Some(3L)),
        EntityVersion.expected(None, Some(4)) == Right(Some(4L)),
        EntityVersion.expected(Some("4"), Some(4)) == Right(Some(4L)),
        EntityVersion.expected(Some("\"3\""), Some(4)).isLeft,
        EntityVersion.expected(None, None) == Right(None),
        EntityVersion.expected(Some("*"), None).isLeft,
      )
    },
  )
