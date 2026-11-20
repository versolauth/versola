package versola.edge.attestation

import versola.edge.attestation.AttestationFailure.Kind
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.metrics.{Metric, MetricLabel}
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

  /** A service account with a key of its own, so that the live decoder can sign its assertion. */
  private val googleAccount =
    val generator = java.security.KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val der = generator.generateKeyPair().getPrivate.getEncoded
    val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(der) + "\n-----END PRIVATE KEY-----\n"
    PlayIntegrity.ServiceAccount.parse(
      Json.Obj("client_email" -> Json.Str("edge@project.iam"), "private_key" -> Json.Str(pem), "token_uri" -> Json.Str("http://google.test/token")).toJson,
    ).toOption.get

  private def calls(result: String): UIO[Double] =
    Metric.counter("attestation_play_integrity_calls_total").tagged(MetricLabel("result", result)).value.map(_.count)

  private val grantedToken = Response.json("""{"access_token":"bearer","expires_in":3600}""")

  /** One decode through the live decoder, against a stand-in for Google that answers the token endpoint and the decode endpoint, and how
    * each of the four results moved. */
  private def decodeAgainst(decode: Response, token: Response = grantedToken) =
    for
      _ <- TestClient.addRoutes(
        Handler.fromFunction[Request](request => if request.url.path.encode.endsWith("/token") then token else decode).toRoutes,
      )
      client <- ZIO.service[Client]
      decoder <- PlayIntegrity.GoogleDecoder.make(googleAccount, "http://google.test/v1/{package}/decodeIntegrityToken", client)
      before <- ZIO.foreach(List("ok", "refused", "quota", "error"))(r => calls(r).map(r -> _)).map(_.toMap)
      outcome <- decoder.decode("com.example.app", "the-token").exit
      after <- ZIO.foreach(List("ok", "refused", "quota", "error"))(r => calls(r).map(r -> _)).map(_.toMap)
    yield (outcome, after.map((r, n) => r -> (n - before(r))))

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
    suite("a call to Google is counted by what it came to (#1042)")(
      test("a verdict Google decoded is ok") {
        for (outcome, moved) <- decodeAgainst(Response.json("""{"tokenPayloadExternal":{"requestDetails":{}}}"""))
        yield assertTrue(outcome.isSuccess, moved == Map("ok" -> 1.0, "refused" -> 0.0, "quota" -> 0.0, "error" -> 0.0))
      },
      test("a token Google cannot decode is refused, which is the app's") {
        for (outcome, moved) <- decodeAgainst(Response.status(Status.BadRequest))
        yield assertTrue(outcome.isFailure, moved == Map("ok" -> 0.0, "refused" -> 1.0, "quota" -> 0.0, "error" -> 0.0))
      },
      test("a spent quota is quota, and a defect, not the app's failure") {
        for (outcome, moved) <- decodeAgainst(Response.status(Status.TooManyRequests))
        yield assertTrue(
          outcome.causeOption.exists(_.isDie),
          moved == Map("ok" -> 0.0, "refused" -> 0.0, "quota" -> 1.0, "error" -> 0.0),
        )
      },
      test("any other answer of Google is an error, and so is an answer that is not a verdict") {
        for
          (denied, movedDenied) <- decodeAgainst(Response.status(Status.Forbidden))
          (unreadable, movedUnreadable) <- decodeAgainst(Response.json("""{"something":"else"}"""))
        yield assertTrue(
          denied.causeOption.exists(_.isDie),
          movedDenied == Map("ok" -> 0.0, "refused" -> 0.0, "quota" -> 0.0, "error" -> 1.0),
          unreadable.isFailure,
          movedUnreadable == Map("ok" -> 0.0, "refused" -> 0.0, "quota" -> 0.0, "error" -> 1.0),
        )
      },
      test("a token endpoint that refuses the service account is an error too: nothing reached the decode endpoint") {
        for (outcome, moved) <- decodeAgainst(Response.json("""{"tokenPayloadExternal":{}}"""), token = Response.status(Status.Unauthorized))
        yield assertTrue(outcome.causeOption.exists(_.isDie), moved == Map("ok" -> 0.0, "refused" -> 0.0, "quota" -> 0.0, "error" -> 1.0))
      },
    ).provideSomeLayer[Scope](TestClient.layer) @@ TestAspect.sequential,
  )
