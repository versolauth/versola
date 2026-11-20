package versola.edge.attestation

import versola.edge.attestation.AttestationFailure.Kind
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.Base64

/** What a Play Integrity verdict has to say before edge accepts it (#423), against the verdict Google's API returns. Google itself
  * is not reached here: its decoding is a stub, and the call is the one thing only a project with the API enabled can show. */
object PlayIntegritySpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-11-15T10:00:00Z")
  private val digest = "ab" * 32
  private val app = AndroidApp("com.example.app", Set(digest), playIntegrity = true)
  private val nonce = AttestationService.integrityNonce("mobile-app", "jkt", "jti-1")

  private def b64(hexDigest: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(hexDigest.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)

  private def verdict(
      pkg: String = "com.example.app",
      nonce: String = nonce,
      at: Instant = now,
      recognition: String = "PLAY_RECOGNIZED",
      digests: List[String] = List(digest),
      device: List[String] = List("MEETS_DEVICE_INTEGRITY", "MEETS_BASIC_INTEGRITY"),
  ): Json.Obj =
    s"""{
       |  "requestDetails": {"requestPackageName": "$pkg", "nonce": "$nonce", "timestampMillis": "${at.toEpochMilli}"},
       |  "appIntegrity": {"appRecognitionVerdict": "$recognition", "packageName": "$pkg",
       |                   "certificateSha256Digest": [${digests.map(d => "\"" + b64(d) + "\"").mkString(",")}]},
       |  "deviceIntegrity": {"deviceRecognitionVerdict": [${device.map("\"" + _ + "\"").mkString(",")}]}
       |}""".stripMargin.fromJson[Json.Obj].toOption.get

  private def check(payload: Json.Obj, at: Instant = now) = PlayIntegrity.check(payload, app, nonce, at, 5.minutes)

  private def refused(result: Either[AttestationFailure, Unit]) = result.left.toOption.map(_.kind)

  /** A decoder that answers with the given verdict and remembers what it was asked. */
  private def service(answer: Either[AttestationFailure, Json.Obj]) =
    for asked <- Ref.make(List.empty[(String, String)])
    yield (
      asked,
      PlayIntegrity.make(
        new PlayIntegrity.Decoder:
          override def decode(packageName: String, token: String): IO[AttestationFailure, Json.Obj] =
            asked.update((packageName, token) :: _) *> ZIO.fromEither(answer),
        5.minutes,
      ),
    )

  def spec = suite("Play Integrity verdict")(
    test("a recognised build on a device that meets the bar, for this request, is accepted") {
      assertTrue(check(verdict()).isRight)
    },
    test("the nonce is the request's own hash in URL-safe base64, and another request's is refused") {
      assertTrue(
        nonce == Base64.getUrlEncoder.withoutPadding.encodeToString(AttestationService.clientDataHash("mobile-app", "jkt", "jti-1")),
        nonce != AttestationService.integrityNonce("mobile-app", "jkt", "jti-2"),
        nonce != AttestationService.integrityNonce("mobile-app", "other-key", "jti-1"),
        nonce != AttestationService.integrityNonce("other-app", "jkt", "jti-1"),
        refused(check(verdict(nonce = AttestationService.integrityNonce("mobile-app", "jkt", "jti-2")))).contains(Kind.Mismatch),
      )
    },
    test("a verdict for another package, an app Play does not recognise or a certificate the client does not name is refused") {
      assertTrue(
        refused(check(verdict(pkg = "com.other.app"))).contains(Kind.AppNotAccepted),
        refused(check(verdict(recognition = "UNRECOGNIZED_VERSION"))).contains(Kind.AppNotAccepted),
        refused(check(verdict(recognition = "UNEVALUATED"))).contains(Kind.AppNotAccepted),
        refused(check(verdict(digests = List("cd" * 32)))).contains(Kind.AppNotAccepted),
        // Every certificate the build is signed by has to be one the client names, as with the key attestation.
        refused(check(verdict(digests = List(digest, "cd" * 32)))).contains(Kind.AppNotAccepted),
        refused(check(verdict(digests = Nil))).contains(Kind.AppNotAccepted),
      )
    },
    test("a device that does not meet device integrity is refused, whatever else it meets") {
      assertTrue(
        refused(check(verdict(device = List("MEETS_BASIC_INTEGRITY")))).contains(Kind.DeviceNotAccepted),
        refused(check(verdict(device = Nil))).contains(Kind.DeviceNotAccepted),
      )
    },
    test("a verdict that is old, or from the future, is refused; one within the window is not") {
      assertTrue(
        check(verdict(at = now.minusSeconds(299))).isRight,
        refused(check(verdict(at = now.minusSeconds(301)))).contains(Kind.Mismatch),
        refused(check(verdict(at = now.plusSeconds(301)))).contains(Kind.Mismatch),
      )
    },
    test("a verdict missing its parts is malformed or refused, never accepted") {
      val bare = "{}".fromJson[Json.Obj].toOption.get
      assertTrue(
        refused(check(bare)).contains(Kind.Malformed),
        refused(check(verdict().copy(fields = verdict().fields.filterNot(_._1 == "deviceIntegrity")))).contains(Kind.DeviceNotAccepted),
        refused(check(verdict().copy(fields = verdict().fields.filterNot(_._1 == "appIntegrity")))).contains(Kind.AppNotAccepted),
      )
    },
    test("the token is decoded as the build the key attestation named, and only its verdict decides") {
      for
        pair <- service(Right(verdict()))
        (asked, integrity) = pair
        accepted <- integrity.verify(app, "the-token", nonce, now).either
        questions <- asked.get
      yield assertTrue(accepted.isRight, questions == List(("com.example.app", "the-token")))
    },
    test("a token Google cannot decode is the app's failure, and is passed on as it is") {
      for
        pair <- service(Left(AttestationFailure(Kind.Malformed, "Google could not decode the integrity token")))
        (_, integrity) = pair
        outcome <- integrity.verify(app, "garbage", nonce, now).either
      yield assertTrue(outcome.left.toOption.map(_.kind).contains(Kind.Malformed))
    },
    test("an edge with no Play Integrity settings cannot meet a policy that asks for one, and says so") {
      for outcome <- PlayIntegrity.unconfigured.verify(app, "token", nonce, now).either
      yield assertTrue(outcome.left.toOption.map(_.kind).contains(Kind.Unsupported))
    },
    test("a service account key file is read, and one that is not a key is refused without echoing it") {
      val bad = PlayIntegrity.ServiceAccount.parse("""{"client_email":"a@b","private_key":"secret-material","token_uri":"https://t"}""")
      val notAKey = PlayIntegrity.ServiceAccount.parse("not json")
      assertTrue(
        bad.isLeft, !bad.left.toOption.exists(_.contains("secret-material")),
        notAKey.isLeft,
      )
    },
  )
