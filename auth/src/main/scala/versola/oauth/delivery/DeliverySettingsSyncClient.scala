package versola.oauth.delivery

import versola.oauth.client.CentralSyncTokenService
import versola.util.delivery.{SmsProviderSettings, SmtpSettings}
import versola.util.{Base64, CacheSource, CoreConfig, Email, SecurityService}
import zio.http.{Method, Request, Status, URL}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, UIO, URLayer, ZIO, ZLayer}

import java.nio.charset.StandardCharsets

/** Fetches from central the SMTP server and the SMS provider an administrator saved in the console, with their passwords
  * decrypted. Central sends a password encrypted under the secret it shares with auth, the way it sends client secrets;
  * the body, and so the configuration snapshot it is recorded in, never holds one in the clear.
  *
  * A block central does not hold is absent, and auth then reads the matching block of its own `auth.conf`
  * (see [[DeliveryChannels]]). A block central holds but auth cannot read (a password that does not decrypt, an address
  * or a method auth does not accept) is [[DeliverySettingsSyncClient.Saved.Unusable]], not a failure of the whole fetch:
  * one bad block must not stop auth from starting or take the other channel with it.
  *
  * A central that is older than auth has no such endpoint and answers `404`. That is reported as
  * [[DeliverySettingsSyncClient.EndpointMissing]], and it is [[DeliveryChannels]] that decides what it means: nothing is
  * saved if it is the first answer auth ever got, a failed refresh if it is not.
  */
trait DeliverySettingsSyncClient extends CacheSource[DeliverySettingsSyncClient.Fetched]:
  def getAll: Task[DeliverySettingsSyncClient.Fetched]

object DeliverySettingsSyncClient:

  /** A block central holds. `Unusable` is one that cannot be turned into settings; the channel it belongs to then sends
    * nothing, and does not fall back to `auth.conf`: an administrator saved something, and silently doing something else
    * is the wrong way to find out it is broken.
    */
  enum Saved[+A]:
    /** Switched off, the block stays saved and sends nothing. */
    case Usable(settings: A, enabled: Boolean)
    case Unusable

  case class Fetched(smtp: Option[Saved[SmtpSettings]], otpProvider: Option[Saved[SmsProviderSettings]])

  object Fetched:
    val empty: Fetched = Fetched(smtp = None, otpProvider = None)

  /** Central answered `404`: it has no delivery settings endpoint. */
  final class EndpointMissing extends RuntimeException("central has no delivery settings endpoint")

  val live: URLayer[CoreConfig & CentralSyncTokenService & SecurityService, DeliverySettingsSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  private val Methods = Set("GET", "POST", "PUT", "PATCH")

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
      securityService: SecurityService,
  ) extends DeliverySettingsSyncClient:
    private val SyncURL = config.central.url / "configuration" / "delivery-settings" / "sync"

    override def getAll: Task[Fetched] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(SyncURL)).flatMap: response =>
          if response.status == Status.NotFound then ZIO.fail(EndpointMissing())
          else response.bodyAs[SyncWire].flatMap(toFetched)

    private def toFetched(wire: SyncWire): Task[Fetched] =
      for
        smtp <- ZIO.foreach(wire.smtp)(block => saved(smtpBlock(block, wire.smtpPassword)))
        otpProvider <- ZIO.foreach(wire.otpProvider)(block => saved(otpProviderBlock(block, wire.otpProviderPassword)))
      yield Fetched(smtp = smtp, otpProvider = otpProvider)

    /** What could not be read becomes [[Saved.Unusable]]; the reason is not kept, because it may come from a value. */
    private def saved[A](block: Task[Saved.Usable[A]]): UIO[Saved[A]] =
      block.fold(_ => Saved.Unusable, usable => usable)

    private def smtpBlock(block: SmtpWire, encryptedPassword: Option[String]): Task[Saved.Usable[SmtpSettings]] =
      for
        password <- ZIO.foreach(encryptedPassword)(decrypt)
        from <- ZIO.fromEither(Email.from(block.from)).mapError(RuntimeException(_))
      yield Saved.Usable(
        settings = SmtpSettings(
          host = block.host.trim,
          port = block.port,
          username = block.username,
          password = password,
          from = from,
          subject = block.subject,
          startTls = block.startTls,
        ),
        enabled = block.enabled,
      )

    private def otpProviderBlock(block: OtpProviderWire, encryptedPassword: Option[String]): Task[Saved.Usable[SmsProviderSettings]] =
      for
        password <- ZIO.foreach(encryptedPassword)(decrypt)
        _ <- ZIO.fail(RuntimeException("an SMS method auth does not use")).unless(Methods.contains(block.method))
        url <- ZIO.fromEither(URL.decode(block.url)).mapError(RuntimeException(_))
      yield Saved.Usable(
        settings = SmsProviderSettings(
          method = Method.fromString(block.method),
          url = url,
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

  private case class SmtpWire(
      host: String,
      port: Int,
      username: String,
      from: String,
      subject: String,
      startTls: Boolean,
      enabled: Boolean,
  ) derives JsonCodec

  private case class OtpProviderWire(
      method: String,
      url: String,
      username: Option[String] = None,
      body: Map[String, String] = Map.empty,
      enabled: Boolean,
  ) derives JsonCodec

  private case class SyncWire(
      stored: Boolean,
      smtp: Option[SmtpWire] = None,
      smtpPassword: Option[String] = None,
      otpProvider: Option[OtpProviderWire] = None,
      otpProviderPassword: Option[String] = None,
  ) derives JsonCodec
