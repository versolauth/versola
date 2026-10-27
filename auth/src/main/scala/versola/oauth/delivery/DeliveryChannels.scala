package versola.oauth.delivery

import versola.oauth.client.CentralSyncTokenService
import versola.oauth.delivery.DeliverySettingsSyncClient.{EndpointMissing, Fetched, Saved}
import versola.util.delivery.{EmailSender, SmsProviderSettings, SmsSender, SmtpSettings}
import versola.util.{CacheSource, ConfigSnapshot, CoreConfig, ReloadingCache, SecurityService}
import zio.http.Client
import zio.{Duration, Ref, Scope, Task, UIO, ZIO, ZLayer}

/** The two channels auth sends one-time codes and temporary passwords through, as they are configured now.
  *
  * Each channel is decided on its own, so saving only an SMTP server in the console does not switch off an
  * `otp-provider` block of `auth.conf`:
  *
  *  - a block saved in central that is switched on is used, and the same block of `auth.conf` is then ignored;
  *  - a block saved in central that is switched off sends nothing, whatever `auth.conf` holds: off means off;
  *  - a block saved in central that auth cannot read sends nothing either, and is announced as such: an administrator
  *    saved something, so quietly falling back to a different server would hide that it is broken;
  *  - with no block saved in central, the block of `auth.conf` is used, as it was before the console could save one.
  *
  * Central's blocks reach auth through the configuration cache, so a saved change applies after the next refresh, or at
  * once through the non-prod `/service/configuration/sync`. A sender is built when the configuration is loaded, not per
  * message. A failed refresh keeps the senders already built.
  */
trait DeliveryChannels:
  def email: UIO[Option[EmailSender]]
  def sms: UIO[Option[SmsSender]]

  /** Reloads from central now, instead of at the next refresh interval. For the non-prod `/service/configuration/sync`,
    * which exists so that a test need not wait one out. A failure is logged and leaves the current senders in place.
    */
  def refresh: UIO[Unit]

object DeliveryChannels:

  /** Where a channel's settings come from, which is also what an operator asks when a message does not arrive. */
  enum Origin:
    case Central
    case Config
    case Off
    case Invalid
    case Absent

  case class Resolved(
      email: Option[EmailSender],
      emailOrigin: Origin,
      emailOverridesConfig: Boolean,
      sms: Option[SmsSender],
      smsOrigin: Origin,
      smsOverridesConfig: Boolean,
  )

  /** The senders already built, for a test or a caller that has no central to ask. */
  def fixed(emailSender: Option[EmailSender], smsSender: Option[SmsSender]): DeliveryChannels =
    new DeliveryChannels:
      override def email: UIO[Option[EmailSender]] = ZIO.succeed(emailSender)
      override def sms: UIO[Option[SmsSender]] = ZIO.succeed(smsSender)
      override def refresh: UIO[Unit] = ZIO.unit

  def resolve(config: CoreConfig, client: Client, central: Fetched): Resolved =
    val (email, emailOrigin) = central.smtp match
      case Some(Saved.Usable(settings, true)) => (Some(EmailSender.smtp(settings)), Origin.Central)
      case Some(Saved.Usable(_, false)) => (None, Origin.Off)
      case Some(Saved.Unusable) => (None, Origin.Invalid)
      case None =>
        config.smtp match
          case Some(smtp) => (Some(EmailSender.smtp(smtpOf(smtp))), Origin.Config)
          case None => (None, Origin.Absent)
    val (sms, smsOrigin) = central.otpProvider match
      case Some(Saved.Usable(settings, true)) => (Some(SmsSender.http(client, settings)), Origin.Central)
      case Some(Saved.Usable(_, false)) => (None, Origin.Off)
      case Some(Saved.Unusable) => (None, Origin.Invalid)
      case None =>
        config.otpProvider match
          case Some(provider) => (Some(SmsSender.http(client, smsOf(provider))), Origin.Config)
          case None => (None, Origin.Absent)
    Resolved(
      email = email,
      emailOrigin = emailOrigin,
      emailOverridesConfig = central.smtp.isDefined && config.smtp.isDefined,
      sms = sms,
      smsOrigin = smsOrigin,
      smsOverridesConfig = central.otpProvider.isDefined && config.otpProvider.isDefined,
    )

  private def smtpOf(config: CoreConfig.SmtpConfig): SmtpSettings =
    SmtpSettings(
      host = config.host,
      port = config.port,
      username = config.username,
      password = Some(config.password),
      from = config.from,
      subject = config.subject,
      startTls = config.startTls,
    )

  private def smsOf(config: CoreConfig.OtpProvider): SmsProviderSettings =
    SmsProviderSettings(
      method = config.method,
      url = config.url,
      username = config.username,
      password = config.password,
      body = config.body,
    )

  val live: ZLayer[Scope & CoreConfig & Client & CentralSyncTokenService & SecurityService & ConfigSnapshot, Throwable, DeliveryChannels] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[CoreConfig]
        client <- ZIO.service[Client]
        sync <- (ZLayer.environment[CoreConfig & CentralSyncTokenService & SecurityService] >>> DeliverySettingsSyncClient.live).build
          .map(_.get[DeliverySettingsSyncClient])
        source <- resolvedSource(config, client, sync)
        channels <- make(source, config.configurationCacheRefreshInterval)
      yield channels

  /** What a missing endpoint means depends on when it is heard. As the very first answer it is a central that is older
    * than auth, which holds nothing: auth reads its own file, as it did before there was a console to save anything in.
    * After central has answered once it is not that: a path that went wrong, a proxy page, a central rolled back. Taking
    * it for "nothing is saved" would send through `auth.conf` what an administrator switched off, so it is a failed
    * refresh, and the senders already built stay.
    */
  private[delivery] def resolvedSource(config: CoreConfig, client: Client, sync: CacheSource[Fetched]): UIO[CacheSource[Resolved]] =
    for
      answeredOnce <- Ref.make(false)
      announced <- Ref.make(Option.empty[(Origin, Origin)])
    yield new CacheSource[Resolved]:
      override def getAll: Task[Resolved] =
        sync.getAll
          .tap(_ => answeredOnce.set(true))
          .catchSome:
            case missing: EndpointMissing =>
              answeredOnce.get.flatMap(answered => if answered then ZIO.fail(missing) else ZIO.succeed(Fetched.empty))
          .flatMap: fetched =>
            val resolved = resolve(config = config, client = client, central = fetched)
            announce(announced, resolved).as(resolved)

  /** The channels over a source of resolved senders: loaded now, reloaded every `interval` and on [[DeliveryChannels.refresh]]. */
  private[delivery] def make(source: CacheSource[Resolved], interval: Duration): ZIO[Scope, Throwable, DeliveryChannels] =
    ReloadingCache
      .make[Resolved](interval, fromSnapshot = true)
      .provideSomeEnvironment[Scope](_.add[CacheSource[Resolved]](source))
      .map(Impl(_, source))

  /** Says where each channel's settings come from, when that changes and at start-up: the only way an operator sees that
    * a saved block has taken over from `auth.conf`, has been switched off, or cannot be read. Never names a value.
    */
  private def announce(announced: Ref[Option[(Origin, Origin)]], resolved: Resolved): UIO[Unit] =
    val origins = (resolved.emailOrigin, resolved.smsOrigin)
    val unreadable = List(
      Option.when(resolved.emailOrigin == Origin.Invalid)("the SMTP block"),
      Option.when(resolved.smsOrigin == Origin.Invalid)("the SMS provider block"),
    ).flatten
    val summary =
      s"Delivery settings: email from ${resolved.emailOrigin}, sms from ${resolved.smsOrigin}" +
        (if resolved.emailOverridesConfig then "; the smtp block of auth.conf is ignored" else "") +
        (if resolved.smsOverridesConfig then "; the otp-provider block of auth.conf is ignored" else "") +
        (if unreadable.nonEmpty then s"; ${unreadable.mkString(" and ")} saved in central cannot be read, check the shared secret and the saved values" else "")
    val log = if unreadable.nonEmpty then ZIO.logWarning(summary) else ZIO.logInfo(summary)
    announced.getAndSet(Some(origins)).flatMap(previous => log.unless(previous.contains(origins)).unit)

  private class Impl(cache: ReloadingCache[Resolved], source: CacheSource[Resolved]) extends DeliveryChannels:
    override def email: UIO[Option[EmailSender]] = cache.get.map(_.email)

    override def sms: UIO[Option[SmsSender]] = cache.get.map(_.sms)

    override def refresh: UIO[Unit] =
      source.getAll
        .flatMap(cache.set)
        .catchAllCause(cause => ZIO.logWarningCause("Could not reload the delivery settings from central; keeping the current ones", cause))
