package versola.util

import zio.json.*
import zio.json.ast.Json
import zio.test.*

object JsonMergePatchSpec extends ZIOSpecDefault:

  private def json(text: String): Json = text.fromJson[Json].getOrElse(throw IllegalArgumentException(text))
  private def merged(target: String, patch: String): Json = JsonMergePatch(json(target), json(patch))

  def spec = suite("JsonMergePatch")(
    test("the examples of RFC 7396 appendix A") {
      assertTrue(
        merged("""{"a":"b"}""", """{"a":"c"}""") == json("""{"a":"c"}"""),
        merged("""{"a":"b"}""", """{"b":"c"}""") == json("""{"a":"b","b":"c"}"""),
        merged("""{"a":"b"}""", """{"a":null}""") == json("""{}"""),
        merged("""{"a":"b","b":"c"}""", """{"a":null}""") == json("""{"b":"c"}"""),
        merged("""{"a":["b"]}""", """{"a":"c"}""") == json("""{"a":"c"}"""),
        merged("""{"a":"c"}""", """{"a":["b"]}""") == json("""{"a":["b"]}"""),
        merged("""{"a":{"b":"c"}}""", """{"a":{"b":"d","c":null}}""") == json("""{"a":{"b":"d"}}"""),
        merged("""{"a":[{"b":"c"}]}""", """{"a":[1]}""") == json("""{"a":[1]}"""),
        merged("""["a","b"]""", """["c","d"]""") == json("""["c","d"]"""),
        merged("""{"a":"b"}""", """["c"]""") == json("""["c"]"""),
        merged("""{"a":"foo"}""", "null") == Json.Null,
        merged("""{"a":"foo"}""", "\"bar\"") == json("\"bar\""),
        merged("""{"e":null}""", """{"a":1}""") == json("""{"e":null,"a":1}"""),
        merged("""[1,2]""", """{"a":"b","c":null}""") == json("""{"a":"b"}"""),
        merged("""{}""", """{"a":{"bb":{"ccc":null}}}""") == json("""{"a":{"bb":{}}}"""),
      )
    },
    test("nested objects merge, a map entry is removed by null, an array replaces, the rest is left as it was") {
      val target =
        """{"blockAt":50,"smsLimits":{"default":{"sentPerHour":10},"byPrefix":{"49":{"sentPerHour":2},"7":{"sentPerHour":3}}},"shadowRules":["a","b"]}"""
      val patch = """{"blockAt":80,"smsLimits":{"byPrefix":{"49":null,"44":{"sentPerHour":1}}},"shadowRules":["c"]}"""
      assertTrue(
        merged(target, patch) == json(
          """{"blockAt":80,"smsLimits":{"default":{"sentPerHour":10},"byPrefix":{"7":{"sentPerHour":3},"44":{"sentPerHour":1}}},"shadowRules":["c"]}""",
        ),
      )
    },
    test("removing a member that is not there changes nothing") {
      assertTrue(merged("""{"a":1}""", """{"b":null}""") == json("""{"a":1}"""))
    },
  )
