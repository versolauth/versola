package versola.edge.attestation

import versola.edge.attestation.AttestationFixtures.*
import zio.test.*

/** The decoder reads what a client chose to send, so what it refuses matters as much as what it reads. */
object CborSpec extends ZIOSpecDefault:

  private def bytes(values: Int*): Array[Byte] = values.map(_.toByte).toArray

  def spec = suite("Cbor")(
    test("it reads the items an attestation object is made of, and gets a value by its key") {
      val item = map(
        "fmt" -> Cbor.Text("apple-appattest"),
        "n" -> Cbor.Unsigned(300),
        "b" -> Cbor.Bytes(bytes(1, 2, 3)),
        "a" -> Cbor.Arr(Vector(Cbor.Negative(4))),
      )
      val decoded = Cbor.decode(cbor(item))
      assertTrue(
        decoded.toOption.flatMap(_.get("fmt")).flatMap(_.text).contains("apple-appattest"),
        decoded.toOption.flatMap(_.get("n")).contains(Cbor.Unsigned(300)),
        decoded.toOption.flatMap(_.get("b")).flatMap(_.bytes).map(_.toList).contains(List[Byte](1, 2, 3)),
        decoded.toOption.flatMap(_.get("a")).contains(Cbor.Arr(Vector(Cbor.Negative(4)))),
        decoded.toOption.flatMap(_.get("missing")).isEmpty,
      )
    },
    test("it reads a length in every width a definite length takes") {
      val sizes = List(23, 24, 255, 256, 65535, 65536)
      assertTrue(sizes.forall(size => Cbor.decode(cbor(Cbor.Bytes(Array.fill[Byte](size)(7)))).toOption.flatMap(_.bytes).exists(_.length == size)))
    },
    test("an input that stops short of what it announces is refused") {
      assertTrue(Cbor.decode(bytes(0x44, 1, 2)).isLeft, Cbor.decode(bytes(0x19, 1)).isLeft, Cbor.decode(Array.emptyByteArray).isLeft)
    },
    test("bytes after the item are refused, so that two items cannot hide in one") {
      assertTrue(Cbor.decode(bytes(0x01, 0x02)).isLeft)
    },
    test("indefinite lengths, tags and simple values are refused") {
      assertTrue(
        Cbor.decode(bytes(0x5f, 0x41, 1, 0xff)).isLeft,
        Cbor.decode(bytes(0xc0, 0x01)).isLeft,
        Cbor.decode(bytes(0xf6)).isLeft,
      )
    },
    test("a length larger than the input is refused without being allocated") {
      assertTrue(Cbor.decode(bytes(0x5b, 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)).isLeft)
    },
    test("nesting and the number of items are bounded") {
      val deep = Array.fill[Byte](20)(0x81.toByte) :+ 0x00.toByte
      val wide = cbor(Cbor.Arr(Vector.fill(300)(Cbor.Unsigned(1))))
      assertTrue(Cbor.decode(deep).isLeft, Cbor.decode(wide).isLeft)
    },
  )
