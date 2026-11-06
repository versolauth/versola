package versola.oauth.model

import versola.util.UnitSpecBase
import zio.test.*

object UrlEncodedParametersSpec extends UnitSpecBase:

  val spec = suite("UrlEncodedParameters")(
    suite("parse")(
      test("reads each occurrence, in order, decoded as a form is") {
        assertTrue(
          UrlEncodedParameters.parse("a=1&b=two+words&c=%C3%A9") == Some(List("a" -> "1", "b" -> "two words", "c" -> "é")),
        )
      },
      test("keeps a repeated parameter as separate entries instead of joining it") {
        assertTrue(UrlEncodedParameters.parse("state=a&state=b") == Some(List("state" -> "a", "state" -> "b")))
      },
      test("reads a name without a value as empty, and skips empty pairs") {
        assertTrue(UrlEncodedParameters.parse("a&&b=&=c") == Some(List("a" -> "", "b" -> "", "" -> "c")))
      },
      test("reads an empty body as no parameters") {
        assertTrue(UrlEncodedParameters.parse("") == Some(Nil))
      },
      test("refuses a malformed percent-encoding") {
        assertTrue(UrlEncodedParameters.parse("a=%zz").isEmpty, UrlEncodedParameters.parse("%=1").isEmpty)
      },
    ),
    suite("repeated")(
      test("names the parameters that occur more than once, even with the same value") {
        assertTrue(UrlEncodedParameters.repeated(List("a" -> "1", "b" -> "2", "a" -> "1", "c" -> "3", "c" -> "4")) == Set("a", "c"))
      },
      test("is empty when every parameter occurs once") {
        assertTrue(UrlEncodedParameters.repeated(List("a" -> "1", "b" -> "2")).isEmpty)
      },
    ),
  )
