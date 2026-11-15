package versola.util

import zio.*
import zio.config.magnolia.deriveConfig
import zio.test.*

import java.nio.charset.StandardCharsets

object RoutingKeySpec extends ZIOSpecDefault:

  private val cell = RoutingKey.CellId.parse("kz-1").toOption.get

  /** A config block with the one optional key `auth` reads a cell id from. */
  private case class Probe(cellId: Option[RoutingKey.CellId])

  private val random = Array.tabulate[Byte](32)(i => (i * 7).toByte)

  def spec = suite("RoutingKey")(
    suite("CellId")(
      test("accepts lowercase letters, digits and inner hyphens") {
        assertTrue(
          RoutingKey.CellId.parse("kz-1").isRight,
          RoutingKey.CellId.parse("a").isRight,
          RoutingKey.CellId.parse("eu-west-12").isRight,
          RoutingKey.CellId.parse("a" * RoutingKey.CellId.MaxLength).isRight,
        )
      },
      test("rejects anything that could not be told apart from the separator, the version or another cell") {
        assertTrue(
          RoutingKey.CellId.parse("").isLeft,
          RoutingKey.CellId.parse("KZ-1").isLeft,
          RoutingKey.CellId.parse("kz_1").isLeft,
          RoutingKey.CellId.parse("kz.1").isLeft,
          RoutingKey.CellId.parse("-kz").isLeft,
          RoutingKey.CellId.parse("kz-").isLeft,
          RoutingKey.CellId.parse("kz 1").isLeft,
          RoutingKey.CellId.parse("a" * (RoutingKey.CellId.MaxLength + 1)).isLeft,
        )
      },
      test("is read from configuration by the same rule") {
        for
          present <- ConfigProvider.fromMap(Map("cell-id" -> "kz-1")).kebabCase.load(deriveConfig[Probe])
          absent <- ConfigProvider.fromMap(Map.empty).kebabCase.load(deriveConfig[Probe])
          malformed <- ConfigProvider.fromMap(Map("cell-id" -> "KZ.1")).kebabCase.load(deriveConfig[Probe]).exit
        yield assertTrue(
          present.cellId.contains(cell),
          absent.cellId.isEmpty,
          malformed.isFailure,
        )
      },
    ),
    suite("encode and decode")(
      test("round-trips a value through the v1 prefix") {
        val encoded = RoutingKey.encode(cell, "Q2hhbmdl")
        assertTrue(
          encoded == "v1.kz-1.Q2hhbmdl",
          RoutingKey.decode(encoded) == Right(RoutingKey.Decoded(Some(cell), "Q2hhbmdl")),
          RoutingKey.strip(encoded) == "Q2hhbmdl",
        )
      },
      test("reads a value without a prefix as unrouted, untouched") {
        assertTrue(
          RoutingKey.decode("Q2hhbmdl") == Right(RoutingKey.Decoded(None, "Q2hhbmdl")),
          RoutingKey.decode("") == Right(RoutingKey.Decoded(None, "")),
          RoutingKey.strip("Q2hhbmdl") == "Q2hhbmdl",
        )
      },
      test("keeps the separator inside the value") {
        assertTrue(
          RoutingKey.decode("v1.kz-1.a.b") == Right(RoutingKey.Decoded(Some(cell), "a.b")),
        )
      },
      test("refuses a value that announces a routing key and then fails to spell one") {
        assertTrue(
          RoutingKey.decode("v1.").isLeft,
          RoutingKey.decode("v1.kz-1").isLeft,
          RoutingKey.decode("v1.kz-1.").isLeft,
          RoutingKey.decode("v1..Q2hhbmdl").isLeft,
          RoutingKey.decode("v1.KZ-1.Q2hhbmdl").isLeft,
          RoutingKey.decode("v1.kz_1.Q2hhbmdl").isLeft,
          RoutingKey.decode("v1.-kz.Q2hhbmdl").isLeft,
          RoutingKey.strip("v1.kz-1") == "v1.kz-1",
        )
      },
    ),
    suite("Bytes")(
      test("issues the random bytes themselves without a cell, presented as base64url") {
        val issued = RoutingKey.Bytes.issue(None, random)
        val presented = RoutingKey.Bytes.present(issued)
        assertTrue(
          issued.sameElements(random),
          presented == Base64.urlEncode(random),
          RoutingKey.Bytes.parse(presented).exists(_.sameElements(random)),
        )
      },
      test("issues the whole routed string as the bytes under a cell, so a MAC of them covers the prefix") {
        val issued = RoutingKey.Bytes.issue(Some(cell), random)
        val presented = RoutingKey.Bytes.present(issued)
        assertTrue(
          presented == s"v1.kz-1.${Base64.urlEncode(random)}",
          issued.sameElements(presented.getBytes(StandardCharsets.UTF_8)),
          RoutingKey.Bytes.parse(presented).exists(_.sameElements(issued)),
        )
      },
      test("reads a routed value stripped of its key as a different value, not the same one") {
        val issued = RoutingKey.Bytes.issue(Some(cell), random)
        val stripped = RoutingKey.strip(RoutingKey.Bytes.present(issued))
        assertTrue(
          stripped == Base64.urlEncode(random),
          RoutingKey.Bytes.parse(stripped).exists(bytes => bytes.sameElements(random) && !bytes.sameElements(issued)),
        )
      },
      test("reads a value issued before any cell existed, which carries no key") {
        assertTrue(RoutingKey.Bytes.parse(Base64.urlEncode(random)).exists(_.sameElements(random)))
      },
      test("refuses a malformed key or a value that is not base64url behind one") {
        assertTrue(
          RoutingKey.Bytes.parse("").isLeft,
          RoutingKey.Bytes.parse("v1.kz-1.").isLeft,
          RoutingKey.Bytes.parse("v1.KZ-1.Q2hhbmdl").isLeft,
          RoutingKey.Bytes.parse("v1.kz-1.not base64!").isLeft,
          RoutingKey.Bytes.parse("not base64!").isLeft,
        )
      },
      test("presents random bytes that do not spell a routed string as base64url even when they start like one") {
        val lookalike = "v1.kz-1.".getBytes(StandardCharsets.UTF_8) ++ Array[Byte](0, -1, 2)
        val presented = RoutingKey.Bytes.present(lookalike)
        assertTrue(
          presented == Base64.urlEncode(lookalike),
          RoutingKey.Bytes.parse(presented).exists(_.sameElements(lookalike)),
        )
      },
    ),
  )
