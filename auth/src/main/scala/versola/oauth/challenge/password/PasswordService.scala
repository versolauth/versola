package versola.oauth.challenge.password

import versola.auth.model.{Password, PasswordRecord}
import versola.oauth.challenge.password.model.{
  CheckPassword,
  DeliveryChannel,
  PasswordChangeEffect,
  PasswordDeliveryUnavailable,
  PasswordHistoryChanged,
  PasswordReuseError,
  PasswordRevealForbidden,
  TemporaryPasswordGenerationFailed,
}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.OtpTemplateChannel
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.oauth.conversation.otp.{EmailOtpProvider, SmsOtpProvider}
import versola.oauth.revocation.{RevocationCause, RevocationTerms}
import versola.oauth.session.model.SessionRecord
import versola.user.UserRepository
import versola.user.model.UserId
import versola.util.{CoreConfig, Email, EnvName, MAC, Phone, Salt, Secret, SecureRandom, SecurityService}
import zio.prelude.EqualOps
import zio.{Clock, IO, Task, UIO, ZIO, ZLayer}

import java.time.Instant

trait PasswordService:
  def verifyPassword(userId: UserId, password: Password): Task[CheckPassword]

  /** Does the work [[verifyPassword]] does for a user who has a password, for a login that names nobody: one hash of the submitted
    * password, with the same cost and the same secret, whose result is thrown away. Without it an unknown login is answered in
    * a fraction of the time a known one takes (the hash is what takes the time), and the time tells whether the login exists.
    */
  def verifyUnknownUser(password: Password): Task[Unit]

  /** Returns true if the user has at least one non-expired password on record. */
  def hasPassword(userId: UserId): Task[Boolean]

  /** Stores `password` as the user's current one, and applies `change` in the same transaction. Returns the
    * sessions it ended, which is none unless `change` is [[PasswordChangeEffect.EndOtherSessions]]. */
  def setPassword(userId: UserId, password: Password, change: PasswordChangeEffect): IO[Throwable | PasswordReuseError, List[SessionRecord]]

  /** `refuseEarlierTokens` as in [[PasswordRepository.createTemporary]]. */
  def setTemporaryPassword(userId: UserId, password: Password, expiresAt: Instant, refuseEarlierTokens: Option[RevocationTerms]): Task[Unit]

  /** Issues a temporary password and delivers it over the requested channel.
    * Returns the plaintext only for [[DeliveryChannel.show]], which is rejected
    * with [[PasswordRevealForbidden]] in production.
    *
    * The access tokens the user holds from before the reset stop being accepted, recorded with the password
    * (cause `password_changed`); their sessions and refresh tokens are left alone.
    */
  def resetPassword(
      userId: UserId,
      expiresInSeconds: Option[Long],
      channel: Option[DeliveryChannel],
  ): Task[Option[Password]]

object PasswordService:
  def live = ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _))

  /** The salt of the hash [[PasswordService.verifyUnknownUser]] computes. It protects nothing: the result is discarded. */
  private val UnknownUserSalt: Salt = Salt(Array.fill(16)(0.toByte))

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
    override def verifyUnknownUser(password: Password): Task[Unit] =
      securityService.hashPassword(Secret.fromString(password), UnknownUserSalt, config.security.passwordsSecret).unit

    override def hasPassword(userId: UserId): Task[Boolean] =
      Clock.instant.flatMap: now =>
        passwordRepository.list(userId).map(_.exists(_.expiresAt.forall(_.isAfter(now))))

    override def verifyPassword(userId: UserId, password: Password): Task[CheckPassword] =
      for
        now <- Clock.instant
        allPasswords <- passwordRepository.list(userId)
        result <- allPasswords.partition(_.isTemporary) match
          case (Vector(temp, _*), _) if temp.expiresAt.exists(_.isBefore(now)) =>
            ZIO.succeed(CheckPassword.TemporaryExpired)

          case (Vector(temp, _*), _) =>
            check(password, temp).map:
              case true => CheckPassword.Temporary
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

    override def setPassword(
        userId: UserId,
        password: Password,
        change: PasswordChangeEffect,
    ): IO[Throwable | PasswordReuseError, List[SessionRecord]] =
      setPassword(userId, password, change, attemptsLeft = SetPasswordAttempts)

    /** The reuse check reads the history outside the repository's transaction, because it needs the hashing
      * service. The repository refuses the insert if another change got in between ([[PasswordHistoryChanged]]),
      * and the check is then made again against the history that change left. */
    private def setPassword(
        userId: UserId,
        password: Password,
        change: PasswordChangeEffect,
        attemptsLeft: Int,
    ): IO[Throwable | PasswordReuseError, List[SessionRecord]] =
      for
        settings <- configuration.getPasswordHistorySettings
        permanents <- passwordRepository.list(userId).map(_.filter(_.expiresAt.isEmpty))
        _ <- ZIO.foreachDiscard(permanents.take(settings.numDifferent)) { record =>
          securityService
            .hashPassword(Secret.fromString(password), record.salt, config.security.passwordsSecret)
            .flatMap { rehashed =>
              ZIO.fail(PasswordReuseError(settings.numDifferent))
                .when(MAC(rehashed) === MAC(record.password))
            }
        }.mapError(identity[Throwable | PasswordReuseError])
        salt <- secureRandom.nextBytes(16).map(Salt(_))
        hash <- securityService.hashPassword(Secret.fromString(password), salt, config.security.passwordsSecret)
        ended <- passwordRepository
          .create(
            userId = userId,
            passwordHash = Secret(hash),
            salt = salt,
            historySize = settings.historySize,
            numDifferent = settings.numDifferent,
            expectedHead = permanents.map(_.id).maxOption,
            change = change,
          )
          .catchAll:
            case PasswordHistoryChanged if attemptsLeft > 1 => setPassword(userId, password, change, attemptsLeft - 1)
            case PasswordHistoryChanged => ZIO.fail(IllegalStateException(s"The password history of $userId kept changing"))
            case error: PasswordReuseError => ZIO.fail(error)
            case error: Throwable => ZIO.fail(error)
      yield ended

    override def setTemporaryPassword(
        userId: UserId,
        password: Password,
        expiresAt: Instant,
        refuseEarlierTokens: Option[RevocationTerms],
    ): Task[Unit] =
      for
        salt <- secureRandom.nextBytes(16).map(Salt(_))
        hash <- securityService.hashPassword(Secret.fromString(password), salt, config.security.passwordsSecret)
        _ <- passwordRepository.createTemporary(userId, Secret(hash), salt, expiresAt, refuseEarlierTokens)
      yield ()

    override def resetPassword(
        userId: UserId,
        expiresInSeconds: Option[Long],
        channel: Option[DeliveryChannel],
    ): Task[Option[Password]] =
      val reveal = channel.contains(DeliveryChannel.show)
      for
        _ <- ZIO.fail(PasswordRevealForbidden(userId)).when(reveal && env.isProd)
        passwordRegex <- configuration.getPasswordRegex
        // Resolve the recipient before storing anything: if the requested channel has no matching
        // contact we must fail fast, otherwise we would leave an active-but-undisclosed temporary
        // password that locks the user out.
        recipient <- if env.isProd then resolveRecipient(userId, channel) else ZIO.none
        plaintext <- generateTemporaryPassword(passwordRegex)
        ttlSeconds = expiresInSeconds.getOrElse(DefaultTtlSeconds)
        expiresAt <- Clock.instant.map(_.plusSeconds(ttlSeconds))
        terms <- configuration.accessTokenTtls.map(RevocationTerms.widest(RevocationCause.PasswordChanged, _))
        _ <- setTemporaryPassword(userId, plaintext, expiresAt, Some(terms))
        _ <- deliverPassword(plaintext, ttlSeconds, recipient)
          .tapError(_ => passwordRepository.deleteTemporary(userId).ignore)
          .when(env.isProd)
      yield Option.when(reveal)(plaintext)

    /** Resolves the concrete contact for the requested delivery channel, failing with
      * [[PasswordDeliveryUnavailable]] when the user has no matching contact on record.
      */
    private def resolveRecipient(
        userId: UserId,
        channel: Option[DeliveryChannel],
    ): Task[Option[Either[Email, Phone]]] =
      channel match
        case None => ZIO.none
        case Some(DeliveryChannel.show) => ZIO.none
        case Some(deliveryChannel) =>
          userRepository.find(userId).flatMap: userOpt =>
            val contact = deliveryChannel match
              case DeliveryChannel.email => userOpt.flatMap(_.email).map(Left(_))
              case DeliveryChannel.sms => userOpt.flatMap(_.phone).map(Right(_))
              case DeliveryChannel.show => None
            contact match
              case Some(value) => ZIO.some(value)
              case None => ZIO.fail(PasswordDeliveryUnavailable(userId, deliveryChannel))

    private def deliverPassword(
        password: Password,
        ttlSeconds: Long,
        recipient: Option[Either[Email, Phone]],
    ): Task[Unit] =
      recipient match
        case None => ZIO.unit
        case Some(contact) =>
          for
            template <- configuration.getPasswordTemplate(
              contact.fold(_ => OtpTemplateChannel.email, _ => OtpTemplateChannel.sms),
              None,
            )
            message = renderPasswordTemplate(template, password, ttlSeconds)
            _ <- contact match
              case Left(email) => emailOtpProvider.send(email, message)
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
          val upper = TempUpperAlpha(r.nextInt(TempUpperAlpha.length)).toString
          val lower = TempLowerAlpha(r.nextInt(TempLowerAlpha.length)).toString
          val digit = TempDigits(r.nextInt(TempDigits.length)).toString
          val special = TempSpecials(r.nextInt(TempSpecials.length)).toString
          val rest = (1 to TempPasswordLen - 4).map(_ => TempAllChars(r.nextInt(TempAllChars.length))).mkString
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
        case (Some(pwd), _) => ZIO.succeed(Password(pwd))
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

    /** How often a password change re-checks reuse after another change got in between. */
    private val SetPasswordAttempts = 3
