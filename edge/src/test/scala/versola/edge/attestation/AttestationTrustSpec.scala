package versola.edge.attestation

import versola.edge.EdgeConfig
import zio.*
import zio.http.*
import zio.test.*

import java.math.BigInteger
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/** What attestation is trusted against: that the roots edge ships are the ones it says, and that Google's list is read in the forms
  * Google writes it and never takes an attestation down with it. */
object AttestationTrustSpec extends ZIOSpecDefault:

  private val statusUrl = "https://android.example/attestation/status"

  private def settings(enabled: Boolean = true) = EdgeConfig.Attestation(
    androidRevocationUrl = URL.decode(statusUrl).toOption.get,
    androidRevocationEnabled = enabled,
    revocationRefreshInterval = 1.hour,
  )

  private val list =
    """{"entries":{"6681152659205225093":{"status":"REVOKED","reason":"KEY_COMPROMISE"},""" +
      """"c35747a084470c3135aeefe2b8d40cd6":{"status":"REVOKED","reason":"SOFTWARE_FLAW"}}}"""

  private def trust(answer: Response, enabled: Boolean = true) =
    for
      _ <- TestClient.addRoutes(Handler.succeed(answer).toRoutes)
      client <- ZIO.service[Client]
      trust <- AttestationTrust.make(settings(enabled), client)
    yield trust

  def spec = suite("AttestationTrust")(
    test("the roots that ship with edge are Apple's App Attestation root and Google's key attestation roots, by fingerprint") {
      for
        trust <- trust(Response.json(list))
        fingerprint = (certificate: X509Certificate) =>
          AttestationFixtures.hex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded))
      yield assertTrue(
        trust.appleRoots.map(fingerprint) == Set("1cb9823ba28ba6ad2d33a006941de2ae4f513ef1d4e831b9f7e0fa7b6242c932"),
        trust.androidRoots.map(fingerprint) == Set(
          "cedb1cb6dc896ae5ec797348bce9286753c2b38ee71ce0fbe34a9a1248800dfc",
          "6d9db4ce6c5c0b293166d08986e05774a8776ceb525d9e4329520de12ba4bcc0",
        ),
      )
    },
    test("a serial on Google's list is revoked, written in decimal or in hex, and one that is not on it is not") {
      for
        trust <- trust(Response.json(list))
        revoked <- trust.androidRevocation
      yield assertTrue(
        revoked(BigInteger("6681152659205225093")),
        revoked(BigInteger("c35747a084470c3135aeefe2b8d40cd6", 16)),
        !revoked(BigInteger.valueOf(42)),
        !revoked(BigInteger("6681152659205225094")),
      )
    },
    test("a list that cannot be fetched leaves nothing revoked, and does not take the attestation down") {
      for
        failing <- trust(Response.status(Status.InternalServerError))
        garbled <- trust(Response.json("""{"entries": 3}"""))
        failingRevoked <- failing.androidRevocation
        garbledRevoked <- garbled.androidRevocation
      yield assertTrue(!failingRevoked(BigInteger.ONE), !garbledRevoked(BigInteger.ONE), failing.androidRoots.nonEmpty)
    },
    test("Google is not asked until an attestation needs the list, and then once, however many ask") {
      for
        asked <- Ref.make(0)
        _ <- TestClient.addRoutes(Handler.fromZIO(asked.update(_ + 1).as(Response.json(list))).toRoutes)
        client <- ZIO.service[Client]
        trust <- AttestationTrust.make(settings(), client)
        atStart <- asked.get
        _ <- trust.androidRevocation
        _ <- trust.androidRevocation
        afterwards <- asked.get
      yield assertTrue(atStart == 0, afterwards == 1)
    },
    test("everyone who asks while the first fetch runs waits for it, so none checks against a list that is empty only because it was second") {
      for
        asked <- Ref.make(0)
        slow = Handler.fromZIO(asked.update(_ + 1) *> ZIO.sleep(300.millis).as(Response.json(list))).toRoutes
        _ <- TestClient.addRoutes(slow)
        client <- ZIO.service[Client]
        trust <- AttestationTrust.make(settings(), client)
        results <- ZIO.foreachPar(1 to 6)(_ => trust.androidRevocation.map(_(BigInteger("6681152659205225093"))))
        count <- asked.get
      yield assertTrue(results.forall(identity), count == 1)
    },
    test("a first fetch that failed is tried again soon, not after the whole interval") {
      for
        asked <- Ref.make(0)
        answer = (n: Int) => if n == 1 then Response.status(Status.BadGateway) else Response.json(list)
        flaky = Handler.fromZIO(asked.updateAndGet(_ + 1).map(answer)).toRoutes
        _ <- TestClient.addRoutes(flaky)
        client <- ZIO.service[Client]
        trust <- AttestationTrust.make(settings(), client, retryAfterFailure = 100.millis)
        first <- trust.androidRevocation.map(_(BigInteger("6681152659205225093")))
        later <- trust.androidRevocation.map(_(BigInteger("6681152659205225093"))).repeatUntil(identity).timeout(5.seconds)
      yield assertTrue(!first, later.contains(true))
    },
    test("with the list switched off, Google is not asked") {
      for
        asked <- Ref.make(0)
        _ <- TestClient.addRoutes(Handler.fromZIO(asked.update(_ + 1).as(Response.json(list))).toRoutes)
        client <- ZIO.service[Client]
        trust <- AttestationTrust.make(settings(enabled = false), client)
        revoked <- trust.androidRevocation
        count <- asked.get
      yield assertTrue(count == 0, !revoked(BigInteger("6681152659205225093")))
    },
    test("roots in a file replace the bundled ones, and a file with none is refused") {
      val file = Files.createTempFile("roots", ".pem")
      val empty = Files.createTempFile("empty", ".pem")
      val pem = AttestationFixtures.google().root.certificate
      val body = Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(pem.getEncoded)
      Files.writeString(file, s"-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n")
      for
        client <- ZIO.service[Client]
        replaced <- AttestationTrust.make(settings().copy(androidRoots = Some(file.toString), androidRevocationEnabled = false), client)
        refused <- AttestationTrust.make(settings().copy(appleRoots = Some(empty.toString), androidRevocationEnabled = false), client).either
      yield assertTrue(replaced.androidRoots == Set(pem), refused.isLeft)
    },
  ).provideSomeLayer[Scope](TestClient.layer) @@ TestAspect.withLiveClock
