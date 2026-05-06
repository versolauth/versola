package versola.util

import zio.json.*
import zio.test.*

object PatchSpec extends ZIOSpecDefault:

  // Derived case class mirrors real-world usage: Option[Patch[A]] per patchable field.
  case class Payload(value: Option[Patch[String]]) derives JsonCodec

  def spec = suite("Patch")(
    encoderSuite,
    decoderSuite,
    caseClassSuite,
    toUpdateSuite,
  )

  private val encoderSuite = suite("JsonEncoder")(
    test("Deleted encodes to null") {
      assertTrue(Patch.Deleted.toJson == "null")
    },
    test("Modified encodes to the wrapped value") {
      val patch: Patch[String] = Patch.Modified("hello")
      assertTrue(patch.toJson == "\"hello\"")
    },
    test("Deleted is not skipped (isNothing = false)") {
      // isNothing must be false so that Some(Deleted) inside an Option field
      // is included as null rather than omitted from the JSON object.
      val encoder = summon[JsonEncoder[Patch[String]]]
      assertTrue(!encoder.isNothing(Patch.Deleted))
    },
  )

  private val decoderSuite = suite("JsonDecoder")(
    test("null decodes to Deleted") {
      assertTrue("null".fromJson[Patch[String]] == Right(Patch.Deleted))
    },
    test("a JSON string decodes to Modified") {
      assertTrue("\"hello\"".fromJson[Patch[String]] == Right(Patch.Modified("hello")))
    },
    test("a wrong JSON type returns a decoding error") {
      assertTrue("true".fromJson[Patch[String]].isLeft)
    },
  )

  // These three cases are the core contract: absent / null / value must map to
  // three distinct Scala values when the field type is Option[Patch[A]].
  private val caseClassSuite = suite("Option[Patch[A]] field in a derived codec")(
    suite("decode")(
      test("absent key → None") {
        assertTrue("{}".fromJson[Payload] == Right(Payload(None)))
      },
      test("null value → Some(Deleted)") {
        assertTrue("""{"value":null}""".fromJson[Payload] == Right(Payload(Some(Patch.Deleted))))
      },
      test("present value → Some(Modified)") {
        assertTrue("""{"value":"hello"}""".fromJson[Payload] == Right(Payload(Some(Patch.Modified("hello")))))
      },
    ),
    suite("encode")(
      test("None → key absent") {
        assertTrue(Payload(None).toJson == "{}")
      },
      test("Some(Deleted) → null value") {
        assertTrue(Payload(Some(Patch.Deleted)).toJson == """{"value":null}""")
      },
      test("Some(Modified) → present value") {
        assertTrue(Payload(Some(Patch.Modified("hello"))).toJson == """{"value":"hello"}""")
      },
    ),
  )

  private val toUpdateSuite = suite("toUpdate")(
    test("None → (false, None)") {
      val opt: Option[Patch[String]] = None
      assertTrue(opt.toUpdate == (false, None))
    },
    test("Some(Deleted) → (true, None)") {
      val opt: Option[Patch[String]] = Some(Patch.Deleted)
      assertTrue(opt.toUpdate == (true, None))
    },
    test("Some(Modified) → (true, Some(v))") {
      val opt: Option[Patch[String]] = Some(Patch.Modified("hello"))
      assertTrue(opt.toUpdate == (true, Some("hello")))
    },
  )

