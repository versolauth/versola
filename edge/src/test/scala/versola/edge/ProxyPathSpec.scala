package versola.edge

import zio.http.Path
import zio.test.*

object ProxyPathSpec extends ZIOSpecDefault:

  /** Built from the segments, so that what is decoded (a `/` inside a segment) is what a route would hand over. */
  private def path(segments: String*): Path = segments.foldLeft(Path.root)(_ / _)

  private val refused = List(
    "an encoded slash, which is a separator once decoded" -> path("users", "a/b"),
    "a backslash" -> path("users", "a\\b"),
    "a NUL" -> path("users", "a\u0000b"),
    "a line feed" -> path("users", "a\nb"),
    "a dot segment" -> path("users", ".."),
    "a current-directory segment" -> path("users", "."),
    "a dot segment with a path parameter, which a resource drops before it normalises" -> path("users", "..;x"),
    "a dot segment with an empty path parameter" -> path("users", "..;"),
    "a dot segment that is encoded once more" -> path("users", "%2e%2e"),
    "a dot segment that is encoded in upper case" -> path("users", "%2E%2E"),
    "a dot segment that is encoded twice more" -> path("users", "%252e%252e"),
    "a separator that is encoded once more" -> path("users", "a%2Fb"),
    "a separator in lower case that is encoded once more" -> path("users", "a%2fb"),
    "a backslash that is encoded once more" -> path("users", "a%5Cb"),
    "a control character that is encoded once more" -> path("users", "a%00b"),
  )

  private val refusedTests = refused.map: (name, value) =>
    test(name) {
      assertTrue(ProxyPath.segments(value).isEmpty)
    }

  def spec = suite("ProxyPath")(
    test("reads the segments of an ordinary path") {
      assertTrue(
        ProxyPath.segments(Path.decode("/users/user-1/orders")) == Some(Vector("users", "user-1", "orders")),
        ProxyPath.segments(Path.root) == Some(Vector.empty),
        ProxyPath.segments(Path.decode("/users/")) == Some(Vector("users")),
      )
    },
    test("keeps a segment with a space, a percent sign, a dot or a semicolon that is not a dot segment") {
      assertTrue(
        ProxyPath.segments(path("users", "a b")) == Some(Vector("users", "a b")),
        ProxyPath.segments(path("users", "100%")) == Some(Vector("users", "100%")),
        ProxyPath.segments(path("users", "a.b", "...", ".hidden", "a;b")) ==
          Some(Vector("users", "a.b", "...", ".hidden", "a;b")),
      )
    },
    suite("refuses")(refusedTests*),
    test("refuses a path when only one of its segments is unsafe") {
      assertTrue(ProxyPath.segments(path("users", "user-1", "a/b", "orders")).isEmpty)
    },
  )
