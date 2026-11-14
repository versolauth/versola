package versola.edge.attestation

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.edge.EdgeConfig
import versola.edge.attestation.AttestationCrypto.{equal, malformed}
import versola.edge.attestation.AttestationFailure.Kind
import zio.http.{Body, Client, Header, MediaType, Request}
import zio.json.*
import zio.json.ast.Json
import zio.{Duration, IO, Ref, UIO, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.{Base64, Date}

/** The Play Store's word on an Android install (#423), for a build whose policy asks for it: that the app is the genuine build Play
  * recognises, on a device that meets Android's integrity bar. Key Attestation says the key is held in hardware and which package
  * and signing certificate the app has; the verdict is Google's statement about the install itself, which is what a repackaged app
  * on a genuine device cannot make.
  *
  * The app asks Play Integrity for a token with a nonce that is a hash of the request (client, DPoP key, the proof's `jti`), edge has
  * Google decode it, and the verdict is held to the policy. A token is therefore good for one request, and for one key.
  */
trait PlayIntegrity:

  /** Fails with the reason the verdict was not accepted. A fault of Google's service, or of this edge's credential for it, is a
    * defect and not a failure: the app did nothing wrong, and `enforce` answers it as the server error it is, which the app retries. */
  def verify(app: AndroidApp, token: String, nonce: String, now: Instant): IO[AttestationFailure, Unit]

object PlayIntegrity:

  /** An edge with no `play-integrity` settings: a policy that asks for the verdict cannot be met here, which is said as it is. */
  val unconfigured: PlayIntegrity = new PlayIntegrity:
    override def verify(app: AndroidApp, token: String, nonce: String, now: Instant): IO[AttestationFailure, Unit] =
      ZIO.fail(AttestationFailure(Kind.Unsupported, "this edge is not set up to verify Play Integrity"))

  /** Google's decoding of a token: the `tokenPayloadExternal` object. */
  trait Decoder:
    def decode(packageName: String, token: String): IO[AttestationFailure, Json.Obj]

  def make(decoder: Decoder, maxAge: Duration): PlayIntegrity = new PlayIntegrity:
    override def verify(app: AndroidApp, token: String, nonce: String, now: Instant): IO[AttestationFailure, Unit] =
      decoder.decode(app.packageName, token).flatMap(payload => ZIO.fromEither(check(payload, app, nonce, now, maxAge)))

  private val PlayRecognized = "PLAY_RECOGNIZED"
  private val MeetsDeviceIntegrity = "MEETS_DEVICE_INTEGRITY"

  /** The verdict held to what the policy accepts and to the request it must be for. */
  def check(payload: Json.Obj, app: AndroidApp, nonce: String, now: Instant, maxAge: Duration): Either[AttestationFailure, Unit] =
    val request = payload.get("requestDetails").flatMap(_.asObject)
    val integrity = payload.get("appIntegrity").flatMap(_.asObject)
    val device = payload.get("deviceIntegrity").flatMap(_.asObject)
    for
      request <- request.toRight(malformed("the verdict has no request details"))
      _ <- Either.cond(
        text(request, "requestPackageName").contains(app.packageName),
        (),
        AttestationFailure(Kind.AppNotAccepted, "the verdict is for another package"),
      )
      _ <- Either.cond(
        text(request, "nonce").exists(received => equal(received.getBytes(StandardCharsets.UTF_8), nonce.getBytes(StandardCharsets.UTF_8))),
        (),
        AttestationFailure(Kind.Mismatch, "the verdict was made for another request"),
      )
      issued <- text(request, "timestampMillis").flatMap(_.toLongOption).map(Instant.ofEpochMilli)
        .toRight(malformed("the verdict has no time"))
      _ <- Either.cond(
        java.time.Duration.between(issued, now).abs().compareTo(maxAge) <= 0,
        (),
        AttestationFailure(Kind.Mismatch, "the verdict is not recent"),
      )
      integrity <- integrity.toRight(AttestationFailure(Kind.AppNotAccepted, "the verdict says nothing about the app"))
      _ <- Either.cond(
        text(integrity, "appRecognitionVerdict").contains(PlayRecognized),
        (),
        AttestationFailure(Kind.AppNotAccepted, "Google Play does not recognise this app"),
      )
      // Google gives the digests in URL-safe base64; the policy has them as hex.
      digests = strings(integrity, "certificateSha256Digest").flatMap(digest => scala.util.Try(Base64.getUrlDecoder.decode(digest)).toOption).map(hex)
      _ <- Either.cond(
        digests.nonEmpty && digests.toSet.subsetOf(app.certificateDigests),
        (),
        AttestationFailure(Kind.AppNotAccepted, "the verdict is for an app signed with a certificate this client does not accept"),
      )
      _ <- Either.cond(
        device.exists(strings(_, "deviceRecognitionVerdict").contains(MeetsDeviceIntegrity)),
        (),
        AttestationFailure(Kind.DeviceNotAccepted, "the device does not meet Android's integrity bar"),
      )
    yield ()

  private def text(obj: Json.Obj, name: String): Option[String] =
    obj.get(name).collect { case Json.Str(value) => value }

  private def strings(obj: Json.Obj, name: String): List[String] =
    obj.get(name).collect { case Json.Arr(values) => values.toList.collect { case Json.Str(value) => value } }.getOrElse(Nil)

  private def hex(bytes: Array[Byte]): String = bytes.map(byte => f"${byte & 0xff}%02x").mkString

  /** The Play Integrity API, with the credential of the configured service account. Without `play-integrity` settings, no policy can
    * ask for a verdict. */
  val live: ZLayer[EdgeConfig & Client, Throwable, PlayIntegrity] =
    ZLayer:
      for
        config <- ZIO.service[EdgeConfig]
        client <- ZIO.service[Client]
        integrity <- config.native.flatMap(_.attestation.playIntegrity) match
          case None => ZIO.succeed(unconfigured)
          case Some(settings) =>
            for
              file <- ZIO.attemptBlocking(java.nio.file.Files.readString(java.nio.file.Path.of(settings.serviceAccountFile)))
              account <- ZIO.fromEither(ServiceAccount.parse(file)).mapError(IllegalStateException(_))
              decoder <- GoogleDecoder.make(account, settings.decodeUrl, client)
            yield make(decoder, settings.maxTokenAge)
      yield integrity

  /** A Google service account, as its JSON key file has it. The key never leaves this object: it is not printed, and `toString`
    * says nothing of it. */
  final class ServiceAccount private (val email: String, key: RSAPrivateKey, val tokenUri: String):
    def assertion(now: Instant): String =
      val claims = JWTClaimsSet.Builder()
        .issuer(email)
        .claim("scope", "https://www.googleapis.com/auth/playintegrity")
        .audience(tokenUri)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(3600)))
        .build()
      val jwt = SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims)
      jwt.sign(RSASSASigner(key))
      jwt.serialize()

    override def toString: String = s"ServiceAccount($email)"

  object ServiceAccount:
    private final case class KeyFile(client_email: String, private_key: String, token_uri: String) derives JsonDecoder

    def parse(json: String): Either[String, ServiceAccount] =
      for
        file <- json.fromJson[KeyFile].left.map(_ => "the Play Integrity service account file is not a Google service account key")
        pem = file.private_key.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").filterNot(_.isWhitespace)
        key <- scala.util.Try(KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder.decode(pem))).asInstanceOf[RSAPrivateKey])
          .toEither.left.map(_ => "the Play Integrity service account file has no usable private key")
      yield ServiceAccount(file.client_email, key, file.token_uri)

  /** Calls `decodeIntegrityToken` as the service account, keeping its access token until shortly before it expires. */
  private final class GoogleDecoder(
      account: ServiceAccount,
      decodeUrl: String,
      client: Client,
      access: Ref.Synchronized[Option[(String, Instant)]],
  ) extends Decoder:

    private val Timeout = 15.seconds

    override def decode(packageName: String, token: String): IO[AttestationFailure, Json.Obj] =
      (for
        bearer <- accessToken
        request = Request.post(
          decodeUrl.replace("{package}", packageName),
          Body.fromString(Json.Obj("integrity_token" -> Json.Str(token)).toJson),
        ).addHeader(Header.ContentType(MediaType.application.json)).addHeader(Header.Authorization.Bearer(bearer))
        outcome <- ZIO.scoped:
          for
            response <- client.batched(request).timeoutFail(RuntimeException("timed out"))(Timeout)
            body <- response.body.asString
          yield (response.status.code, body)
      yield outcome).orDie.flatMap:
        case (200, body) =>
          ZIO.fromEither(body.fromJson[Json.Obj]).mapError(_ => malformed("Google answered the integrity token with something unreadable"))
            .flatMap(answer => ZIO.fromOption(answer.get("tokenPayloadExternal").flatMap(_.asObject)).orElseFail(malformed("the integrity token has no verdict")))
        // The token is not one Google can decode for this package: made for another app, expired, or not a token.
        case (400, _) => ZIO.fail(AttestationFailure(Kind.Malformed, "Google could not decode the integrity token"))
        // Anything else is not the app's doing: this edge's credential, quota, or Google. Not shown to the app.
        case (status, _) => ZIO.die(RuntimeException(s"the Play Integrity API answered $status"))

    private def accessToken: ZIO[Any, Throwable, String] = fetchAccessToken

    private def fetchAccessToken: ZIO[Any, Throwable, String] =
      access.modifyZIO: current =>
        zio.Clock.instant.flatMap: now =>
          current match
            case Some((token, expires)) if expires.isAfter(now.plusSeconds(60)) => ZIO.succeed((token, current))
            case _ =>
              val form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + account.assertion(now)
              ZIO.scoped:
                for
                  response <- client.batched(
                    Request.post(account.tokenUri, Body.fromString(form)).addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`)),
                  ).timeoutFail(RuntimeException("timed out"))(Timeout)
                  body <- response.body.asString
                  _ <- ZIO.fail(RuntimeException(s"Google's token endpoint answered ${response.status.code}")).unless(response.status.isSuccess)
                  parsed <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(RuntimeException(_))
                  token <- ZIO.fromOption(parsed.get("access_token").collect { case Json.Str(value) => value }).orElseFail(RuntimeException("no access token"))
                  lifetime = parsed.get("expires_in").collect { case Json.Num(value) => value.longValue() }.getOrElse(3600L)
                yield (token, Some((token, now.plusSeconds(lifetime))))

  private object GoogleDecoder:
    def make(account: ServiceAccount, decodeUrl: String, client: Client): UIO[Decoder] =
      Ref.Synchronized.make(Option.empty[(String, Instant)]).map(GoogleDecoder(account, decodeUrl, client, _))
