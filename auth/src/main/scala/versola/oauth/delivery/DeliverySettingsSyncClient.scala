package versola.oauth.delivery

import versola.oauth.client.CentralSyncTokenService
import versola.util.delivery.{SmsProviderSettings, SmtpSettings}
import versola.util.{Base64, CacheSource, CoreConfig, Email, SecurityService}
import zio.http.{Method, Request, URL}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

import java.nio.charset.StandardCharsets

/** Fetches from central the SMTP server and the SMS provider an administrator saved in the console, with their passwords
  * decrypted. Central sends a password encrypted under the secret it shares with auth, the way it sends client secrets;
  * the body, and so the configuration snapshot it is recorded in, never holds one in the clear.
  *
  * A block central does not hold is absent, and auth then reads the matching block of its own `auth.conf`
  * (see [[DeliveryChannels]]). A block central holds but auth cannot read (a password that does not decrypt, an address
  * that is not one) fails the fetch: the cache then keeps what it has, or auth does not start if it has nothing yet.
  * So does any answer of central that is not a success, a `404` from a central older than auth included: only a body
  * that central sent as a success is read, so an error page that happens to be valid JSON is never taken for "nothing
  * saved".
  */
trait DeliverySettingsSyncClient extends CacheSource[DeliverySettingsSyncClient.Fetched]:
  def getAll: Task[DeliverySettingsSyncClient.Fetched]

object DeliverySettingsSyncClient:

  /** A block central holds. Switched off (`enabled` false), it stays saved and sends nothing. */
  case class Saved[+A](settings: A, enabled: Boolean)

  case class Fetched(smtp: Option[Saved[SmtpSettings]], otpProvider: Option[Saved[SmsProviderSettings]])

  object Fetched:
    val empty: Fetched = Fetched(smtp = None, otpProvider = None)

  val live: URLayer[CoreConfig & CentralSyncTokenService & SecurityService, DeliverySettingsSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
      securityService: SecurityService,
  ) extends DeliverySettingsSyncClient:
    private val SyncURL = config.central.url / "configuration" / "delivery-settings" / "sync"

    override def getAll: Task[Fetched] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(SyncURL)).flatMap: response =>
          if response.status.isSuccess then response.bodyAs[SyncWire].flatMap(toFetched)
          else ZIO.fail(RuntimeException(s"central answered ${response.status.code} for configuration/delivery-settings/sync"))

    private def toFetched(wire: SyncWire): Task[Fetched] =
      for
        smtp <- ZIO.foreach(wire.smtp)(block => smtpBlock(block, wire.smtpPassword))
        otpProvider <- ZIO.foreach(wire.otpProvider)(block => otpProviderBlock(block, wire.otpProviderPassword))
      yield Fetched(smtp = smtp, otpProvider = otpProvider)

    private def smtpBlock(block: SmtpWire, encryptedPassword: Option[String]): Task[Saved[SmtpSettings]] =
      for
        password <- ZIO.foreach(encryptedPassword)(decrypt)
      yield Saved(
        settings = SmtpSettings(
          host = block.host.trim,
          port = block.port,
          username = block.username,
          password = password,
          from = block.from,
          subject = block.subject,
          startTls = block.startTls,
        ),
        enabled = block.enabled,
      )

    private def otpProviderBlock(block: OtpProviderWire, encryptedPassword: Option[String]): Task[Saved[SmsProviderSettings]] =
      for
        password <- ZIO.foreach(encryptedPassword)(decrypt)
      yield Saved(
        settings = SmsProviderSettings(
          method = Method.fromString(block.method),
          url = block.url,
          username = block.username,
          password = password,
          body = block.body,
        ),
        enabled = block.enabled,
      )

    private def decrypt(value: String): Task[String] =
      for
        encrypted <- ZIO.attempt(Base64.urlDecode(value))
        decrypted <- securityService.decryptAes256(encrypted, config.central.secretKey)
      yield String(decrypted, StandardCharsets.UTF_8)

  private given JsonCodec[Email] = JsonCodec.string.transformOrFail(value => Email.from(value).left.map(_ => "not an email address"), identity)

  private given JsonCodec[URL] = JsonCodec.string.transformOrFail(value => URL.decode(value).left.map(_ => "not an absolute URL"), _.encode)

  private case class SmtpWire(
      host: String,
      port: Int,
      username: String,
      from: Email,
      subject: String,
      startTls: Boolean,
      enabled: Boolean,
  ) derives JsonCodec

  private case class OtpProviderWire(
      method: String,
      url: URL,
      username: Option[String],
      body: Map[String, String],
      enabled: Boolean,
  ) derives JsonCodec

  private case class SyncWire(
      stored: Boolean,
      smtp: Option[SmtpWire],
      smtpPassword: Option[String],
      otpProvider: Option[OtpProviderWire],
      otpProviderPassword: Option[String],
  ) derives JsonCodec
