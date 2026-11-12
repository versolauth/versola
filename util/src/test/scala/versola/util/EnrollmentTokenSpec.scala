package versola.util

import zio.test.*

object EnrollmentTokenSpec extends ZIOSpecDefault:
  def spec = suite("EnrollmentToken")(
    test("carries the address and the secret and gives both back") {
      val token = EnrollmentToken.encode("https://central.example.com", "c2VjcmV0")
      assertTrue(
        token.startsWith("vtk1."),
        token.count(_ == '.') == 2,
        EnrollmentToken.parse(token) == Right(EnrollmentToken.Parsed("https://central.example.com", "c2VjcmV0")),
      )
    },
    test("an address with a port and a path survives") {
      val url = "https://enroll.example.com:8443/central"
      assertTrue(EnrollmentToken.parse(EnrollmentToken.encode(url, "abc")) == Right(EnrollmentToken.Parsed(url, "abc")))
    },
    test("an address that is not https is refused") {
      assertTrue(
        EnrollmentToken.parse(EnrollmentToken.encode("http://central.example.com", "abc")).isLeft,
        EnrollmentToken.parse(EnrollmentToken.encode("ftp://central.example.com", "abc")).isLeft,
        EnrollmentToken.parse(EnrollmentToken.encode("https://user:pw@central.example.com", "abc")).isLeft,
        EnrollmentToken.parse(EnrollmentToken.encode("not a url", "abc")).isLeft,
      )
    },
    test("a token of another shape is refused") {
      assertTrue(
        EnrollmentToken.parse("c2VjcmV0").isLeft,
        EnrollmentToken.parse("vtk1.only-two").isLeft,
        EnrollmentToken.parse("vtk2.aHR0cHM6Ly9hLmI.secret").isLeft,
        EnrollmentToken.parse("vtk1.aHR0cHM6Ly9hLmI.").isLeft,
        EnrollmentToken.parse("vtk1.aHR0cHM6Ly9hLmI.a.b").isLeft,
        EnrollmentToken.parse("").isLeft,
      )
    },
  )
