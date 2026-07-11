package versola.oauth.challenge.password

import versola.auth.model.{Password, PasswordRecord}
import versola.oauth.challenge.password.model.{CheckPassword, DeliveryChannel, PasswordDeliveryUnavailable, PasswordReuseError, TemporaryPasswordGenerationFailed}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.oauth.conversation.otp.{EmailOtpProvider, SmsOtpProvider}
import versola.user.UserRepository
import versola.user.model.UserId
import versola.util.{CoreConfig, Email, EnvName, MAC, Phone, Salt, Secret, SecureRandom, SecurityService}
import zio.prelude.EqualOps
import zio.{Clock, IO, Task, UIO, ZIO, ZLayer}

import java.time.Instant

trait PasswordService:
  def verifyPassword(userId: UserId, password: Password): Task[CheckPassword]

  def setPassword(clientId: ClientId, userId: UserId, password: Password): IO[Throwable | PasswordReuseError, Unit]

  def setTemporaryPassword(userId: UserId, password: Password, expiresAt: Instant): Task[Unit]

  def resetPassword(
      userId: UserId,
      expiresInSeconds: Option[Long],
      channel: Option[DeliveryChannel],
  ): Task[Unit]

object PasswordService:
  def live = ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _))

  class Impl(
      passwordRepository: PasswordRepository,
      securityService: SecurityService,
      secureRandom: SecureRandom,
      config: CoreConfig,
      configuration: OAuthConfigurationService,
      env: EnvName,
      userRepository: UserRepository,
      emailOtpProvider: EmailOtpProvider,
      smsOtpProvider: SmsOtpProvider,
  ) extends PasswordService:
    override def verifyPassword(userId: UserId, password: Password): Task[CheckPassword] =
      for
        now <- Clock.instant
        allPasswords <- passwordRepository.list(userId)
        result <- allPasswords.partition(_.isTemporary) match
          case (Vector(temp, _*), _) if temp.expiresAt.exists(_.isBefore(now)) =>
            ZIO.succeed(CheckPassword.TemporaryExpired)

          case (Vector(temp, _*), _) =>
            check(password, temp).map:
              case true  => CheckPassword.Temporary
              case false => CheckPassword.Failure

          case (_, Vector()) =>
            ZIO.succeed(CheckPassword.Failure)

          case (_, Vector(current, historical*)) =>
            check(password, current).flatMap:
              case true =>
                ZIO.succeed(CheckPassword.Success)
              case false =>
                ZIO.foldLeft(historical)(Option.empty[CheckPassword]) {
                  case (None, record) =>
                    check(password, record)
                      .map(Option.when(_)(CheckPassword.OldPassword(record.createdAt)))
                  case (Some(res), _) => ZIO.none
                }.someOrElse(CheckPassword.Failure)
      yield result

    private def check(password: Password, record: PasswordRecord): Task[Boolean] =
      securityService.hashPassword(Secret.fromString(password), record.salt, config.security.passwordsSecret)
        .map(mac => mac === MAC(record.password))

    override def setPassword(clientId: ClientId, userId: UserId, password: Password): IO[Throwable | PasswordReuseError, Unit] =
      for
        settings <- configuration.getPasswordHistorySettings
        salt <- secureRandom.nextBytes(16).map(Salt(_))
        hash <- securityService.hashPassword(Secret.fromString(password), salt, config.security.passwordsSecret)
        _ <- passwordRepository.create(userId, Secret(hash), salt, settings.historySize, settings.numDifferent)
      yield ()

    override def setTemporaryPassword(userId: UserId, password: Password, expiresAt: Instant): Task[Unit] =
      for
        salt <- secureRandom.nextBytes(16).map(Salt(_))
        hash <- securityService.hashPassword(Secret.fromString(password), salt, config.security.passwordsSecret)
        _ <- passwordRepository.createTemporary(userId, Secret(hash), salt, expiresAt)
      yield ()

    override def resetPassword(
        userId: UserId,
        expiresInSeconds: Option[Long],
        channel: Option[DeliveryChannel],
    ): Task[Unit] =
      for
        passwordRegex <- configuration.getPasswordRegex
        // Resolve the recipient before storing anything: if the requested channel has no matching
        // contact we must fail fast, otherwise we would leave an active-but-undisclosed temporary
        // password that locks the user out.
        recipient <- if env.isProd then resolveRecipient(userId, channel) else ZIO.none
        plaintext <- generateTemporaryPassword(passwordRegex)
        ttlSeconds = expiresInSeconds.getOrElse(DefaultTtlSeconds)
        expiresAt <- Clock.instant.map(_.plusSeconds(ttlSeconds))
        _ <- ZIO.logInfo(s"Generated temporary password for user $userId. Password value - $plaintext").when(!env.isProd)
        _ <- setTemporaryPassword(userId, plaintext, expiresAt)
        _ <- deliverPassword(plaintext, ttlSeconds, recipient)
          .tapError(_ => passwordRepository.deleteTemporary(userId).ignore)
          .when(env.isProd)
      yield ()

    /** Resolves the concrete contact for the requested delivery channel, failing with
      * [[PasswordDeliveryUnavailable]] when the user has no matching contact on record.
      */
    private def resolveRecipient(
        userId: UserId,
        channel: Option[DeliveryChannel],
    ): Task[Option[Either[Email, Phone]]] =
      channel match
        case None => ZIO.none
        case Some(deliveryChannel) =>
          userRepository.find(userId).flatMap: userOpt =>
            val contact = deliveryChannel match
              case DeliveryChannel.email => userOpt.flatMap(_.email).map(Left(_))
              case DeliveryChannel.sms   => userOpt.flatMap(_.phone).map(Right(_))
            contact match
              case Some(value) => ZIO.some(value)
              case None        => ZIO.fail(PasswordDeliveryUnavailable(userId, deliveryChannel))

    private def deliverPassword(
        password: Password,
        ttlSeconds: Long,
        recipient: Option[Either[Email, Phone]],
    ): Task[Unit] =
      recipient match
        case None => ZIO.unit
        case Some(contact) =>
          for
            template <- configuration.getPasswordTemplate(None)
            message   = renderPasswordTemplate(template, password, ttlSeconds)
            _ <- contact match
              case Left(email)  => emailOtpProvider.send(email, message)
              case Right(phone) => smsOtpProvider.send(phone, message)
          yield ()

    private def renderPasswordTemplate(template: OtpTemplate, password: Password, ttlSeconds: Long): String =
      val expiresHours = (ttlSeconds / 3600).toString
      template
        .replace("{{password}}", password)
        .replace("{{expiresHours}}", expiresHours)

    private def generateTemporaryPassword(regex: String): Task[Password] =
      def attempt: UIO[String] =
        secureRandom.execute { r =>
          // Guarantee at least one char from each category
          val upper   = TempUpperAlpha(r.nextInt(TempUpperAlpha.length)).toString
          val lower   = TempLowerAlpha(r.nextInt(TempLowerAlpha.length)).toString
          val digit   = TempDigits(r.nextInt(TempDigits.length)).toString
          val special = TempSpecials(r.nextInt(TempSpecials.length)).toString
          val rest    = (1 to TempPasswordLen - 4).map(_ => TempAllChars(r.nextInt(TempAllChars.length))).mkString
          scala.util.Random(r).shuffle((upper + lower + digit + special + rest).toList).mkString
        }

      // Retry until the password satisfies the tenant's regex (at most MaxGenerationAttempts attempts)
      ZIO.iterate((Option.empty[String], 0)) { case (pwd, n) => pwd.isEmpty && n < MaxGenerationAttempts } {
        case (_, n) =>
          attempt.map { pwd =>
            val ok = scala.util.Try(pwd.matches(regex)).getOrElse(true)
            (Option.when(ok)(pwd), n + 1)
          }
      }.flatMap {
        case (Some(pwd), _)   => ZIO.succeed(Password(pwd))
        case (None, attempts) => ZIO.fail(TemporaryPasswordGenerationFailed(attempts))
      }

    // Safe alphabet for temporary password generation (excludes ambiguous chars: l, I, 0, O, 1)
    private val TempUpperAlpha = "ABCDEFGHJKMNPQRSTUVWXYZ"
    private val TempLowerAlpha = "abcdefghjkmnpqrstuvwxyz"
    private val TempDigits = "23456789"
    private val TempSpecials = "!@#$%^&*"
    private val TempAllChars = TempUpperAlpha + TempLowerAlpha + TempDigits + TempSpecials
    private val TempPasswordLen = 16
    private val MaxGenerationAttempts = 20
    private val DefaultTtlSeconds = 12L * 60 * 60 // 12 hours
