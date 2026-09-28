package versola.edge.nativeapp

import versola.util.{Base64, SecurityService}
import zio.json.{DecoderOps, EncoderOps, JsonCodec, jsonField}
import zio.{IO, Task, ZIO}

import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey

/** What `/native/start` would have stored in a `LoginRecord` for a web login, handed to the app
  * instead, sealed under an edge key (AES-256-GCM) so the app can carry it but neither read nor
  * alter it (#420). Edge keeps no state for native clients: replay is bounded by the single-use
  * `request_uri` and code, and a blob is useless to anyone without the device key it names.
  *
  * @param clientId the client `/native/start` was called for; `complete` refuses the blob on any
  *   other client's path.
  * @param codeVerifier RFC 7636 verifier the pushed `code_challenge` was derived from. Never
  *   leaves edge in the clear.
  * @param state what the authorization response must echo.
  * @param jkt RFC 7638 thumbprint of the device key that signed the start proof, which is also
  *   the `dpop_jkt` pushed to auth. The completing proof must be signed with the same key.
  * @param redirectUri the redirect URI pushed, which `/token` must repeat.
  * @param expiresAt epoch seconds after which the blob is refused.
  */
final case class NativeBlob(
    @jsonField("v") version: Int,
    @jsonField("cid") clientId: String,
    @jsonField("pv") codeVerifier: String,
    @jsonField("st") state: String,
    jkt: String,
    @jsonField("ru") redirectUri: String,
    @jsonField("exp") expiresAt: Long,
) derives JsonCodec

object NativeBlob:
  val CurrentVersion = 1

  /** Why a blob was refused. Deliberately not detailed on the wire: a caller learns only that
    * the blob is not one this edge will redeem. */
  case object Unreadable

  def seal(blob: NativeBlob, key: SecretKey, securityService: SecurityService): Task[String] =
    securityService
      .encryptAes256(blob.toJson.getBytes(StandardCharsets.UTF_8), key)
      .map(Base64.urlEncode)

  /** GCM authenticates as it decrypts, so a blob altered anywhere -- or sealed under another
    * edge's key -- fails here rather than decoding into something plausible. */
  def open(value: String, key: SecretKey, securityService: SecurityService): IO[Unreadable.type, NativeBlob] =
    for
      bytes <- ZIO.attempt(Base64.urlDecode(value)).orElseFail(Unreadable)
      plain <- securityService.decryptAes256(bytes, key).orElseFail(Unreadable)
      blob <- ZIO.fromEither(String(plain, StandardCharsets.UTF_8).fromJson[NativeBlob]).orElseFail(Unreadable)
      _ <- ZIO.fail(Unreadable).unless(blob.version == CurrentVersion)
    yield blob
