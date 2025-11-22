package versola.auth

import versola.auth.model.{AttemptsLeft, AuthId, ConversationStep, DeviceId, EmailVerificationRecord, IssuedTokens, OtpCode, RefreshToken, StartPasskeyResponse}
import versola.user.UserRepository
import versola.user.model.{Email, UserId}
import versola.util.{EnvName, ReloadingCache}
import versola.security.SecureRandom
import zio.*

import java.time.Instant

trait AuthService:
  def sendEmail(
      email: Email,
      deviceId: Option[DeviceId],
  ): Task[AuthId]

  def verifyEmail(
      code: OtpCode,
      authId: AuthId,
  ): IO[Throwable | AttemptsLeft, IssuedTokens]

  def refreshTokens(
      refreshToken: RefreshToken,
      deviceId: DeviceId,
  ): IO[Throwable | Unit, IssuedTokens]

  def logout(
      userId: UserId,
      deviceId: DeviceId,
  ): Task[Unit]

  def startPasskey(
      username: Option[String],
      displayName: Option[String],
  ): Task[StartPasskeyResponse]


object AuthService:

  class Impl(
      envName: EnvName,
      userRepository: UserRepository,
      tokenService: TokenService,
      emailVerificationsRepository: EmailVerificationsRepository,
      secureRandom: SecureRandom,
      bans: ReloadingCache[Set[Email]],
      passkeyRepository: PasskeyRepository,
      conversationService: ConversationService,
  ) extends AuthService:
    private val SentOtpLimit = 2

    override def sendEmail(
        email: Email,
        deviceId: Option[DeviceId],
    ): Task[AuthId] =
      for
        now <- Clock.instant
        token <- emailVerificationsRepository.find(email).flatMap:
          // Если лимит превышен, и при этом 15 минут не прошло, то лжем, что email отправили
          case Some(record) if record.timesSent >= SentOtpLimit && record.expireAt.isAfter(now) =>
            ZIO.succeed(record.authId)

          case recordOpt =>
            generateCodeAndSendEmail(email, deviceId, recordOpt, now)
      yield token

    private def generateCodeAndSendEmail(
        email: Email,
        deviceId: Option[DeviceId],
        recordOpt: Option[EmailVerificationRecord],
        now: Instant,
    ): Task[AuthId] =
      for
        authId <- secureRandom.nextUUIDv7.map(AuthId(_))
        code <- secureRandom.nextNumeric(length = 6).map(OtpCode(_))
        (code, authId) <- recordOpt match
          // Если лимит превышен, и при этом 15 минут прошло, то отправляем email
          case Some(record) if record.timesSent >= SentOtpLimit =>
            val newRecord = EmailVerificationRecord(email, authId, deviceId, code, 0)
            for
              _ <- emailVerificationsRepository.overwrite(newRecord)
              _ <- conversationService.create(authId, ConversationStep.email)
            yield (code, authId)

          // Лимит не превышен, переотправка email
          case Some(record) =>
            emailVerificationsRepository.update(email, code, record.timesSent + 1).as((code, record.authId))

          // Отправка email первый раз
          case None =>
            val record = EmailVerificationRecord(email, authId, deviceId, code, 0)
            emailVerificationsRepository.create(record).flatMap:
              case None =>
                conversationService.create(authId, ConversationStep.email).as((code, authId))
              case Some(previous) =>
                ZIO.succeed((previous.code, previous.authId))

       /* _ <- emailService.sendVerificationEmail(email, code)
          .unlessZIO(bans.get.map(_.contains(email) || envName != EnvName.Prod))
          .unit */
      yield authId

    override def verifyEmail(code: OtpCode, authId: AuthId): IO[Throwable | AttemptsLeft, IssuedTokens] = {
      for
        now <- Clock.instant
        bannedEmails <- bans.get
        tokens <- emailVerificationsRepository.findByAuthId(authId).flatMap:
          case None =>
            ZIO.fail(AttemptsLeft(0))

          case Some(record) if bannedEmails.contains(record.email) =>
            ZIO.fail(AttemptsLeft(1))

          case Some(record) if record.expireAt.isBefore(now) =>
            emailVerificationsRepository.delete(record.email) *> ZIO.fail(AttemptsLeft(0))

          case Some(record) if record.code != code && envName == EnvName.Prod =>
            ZIO.fail(AttemptsLeft(1))

          case Some(record) =>
            for
              _ <- emailVerificationsRepository.delete(record.email)
              (user, wasCreated) <- userRepository.findOrCreateByEmail(record.email)
              _ <- conversationService.updateStep(authId, ConversationStep.completed)
              tokens <- tokenService.issueTokens(user, authId, record.deviceId)
            yield tokens
      yield tokens
    }

    override def logout(
        userId: UserId,
        deviceId: DeviceId,
    ): Task[Unit] =
      tokenService.logout(userId, deviceId)

    override def refreshTokens(refreshToken: RefreshToken, deviceId: DeviceId): IO[Throwable | Unit, IssuedTokens] =
      tokenService.reissueTokens(refreshToken, deviceId)

    override def startPasskey(username: Option[String], displayName: Option[String]): Task[StartPasskeyResponse] =
      // TODO: Implement unified passkey start logic
      // 1. Check if user exists and has passkeys -> authentication flow
      // 2. If no user or no passkeys -> registration flow
      ZIO.succeed(
        StartPasskeyResponse(
          options = """{"publicKey":{"challenge":"placeholder"}}""",
          challenge = "placeholder-challenge",
          flow = "registration",
        ),
      )
