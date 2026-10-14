import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** The metadata document gen-env.scala seeds central with. */
object DefaultServerMetadataSpec extends ZIOSpecDefault:

  private val parsed: Json.Obj =
    defaultServerMetadata("https://auth.example").fromJson[Json.Obj].fold(e => throw AssertionError(e), identity)

  def spec = suite("DefaultServerMetadataSpec")(
    test("is valid JSON carrying the given issuer") {
      assertTrue(parsed.get("issuer").contains(Json.Str("https://auth.example")))
    },
    test("advertises only public subject identifiers: nothing implements pairwise") {
      assertTrue(parsed.get("subject_types_supported").contains(Json.Arr(Json.Str("public"))))
    },
  )
