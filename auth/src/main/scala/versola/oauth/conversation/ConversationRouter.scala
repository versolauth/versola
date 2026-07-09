package versola.oauth.conversation

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthFactor, AuthFactorType, PassedAuthFactor}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, Error}
import versola.util.{Email, Phone, SecureRandom}
import zio.{Task, ZIO, ZLayer}

trait ConversationRouter:
  def getConversation(authId: AuthId): Task[Option[ConversationRecord]]

  def submit(
    authId: AuthId,
    submission: Submission,
    uiLocale: Option[String],
    ipAddress: Option[String],
  ): Task[(ConversationResult.Render, ConversationRecord)]

  /** Begin a discoverable-credentials assertion and return the JSON public-key options.
    * Called by GET /challenge/passkey/options; does NOT go through the submit/render cycle.
    */
  def startPasskeyOptions(authId: AuthId): Task[Option[String]]

object ConversationRouter:
  def live = ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      conversationRepository: ConversationRepository,
      conversationService: ConversationService,
      configService: OAuthConfigurationService,
      secureRandom: SecureRandom,
  ) extends ConversationRouter:

    override def getConversation(authId: AuthId): Task[Option[ConversationRecord]] =
      conversationService.find(authId)

    override def startPasskeyOptions(authId: AuthId): Task[Option[String]] =
      conversationService.find(authId).flatMap:
        case None => ZIO.none
        case Some(record) =>
          record.step match
            case cred: ConversationStep.Credential if record.authFlow.passkey.isDefined =>
              configService.getPasskeySettings(record.clientId).flatMap:
                case None =>
                  ZIO.fail(new Exception("Passkeys not configured for this tenant"))
                case Some(settings) =>
                  conversationService.startPasskeyAssertion(authId, record, cred, settings).map(Some(_))
            case _: ConversationStep.Credential =>
              ZIO.none
            case _ =>
              ZIO.fail(new Exception("startPasskeyAssertion called outside Credential step"))

    override def submit(
        authId: AuthId,
        submission: Submission,
        uiLocale: Option[String],
        ipAddress: Option[String],
    ): Task[(ConversationResult.Render, ConversationRecord)] =
      conversationService.find(authId).flatMap:
        case None => ZIO.fail(Error.BadRequest)
        case Some(conversation) =>
          val updated = withUiLocale(conversation, uiLocale)
          dispatch(authId, updated, submission, ipAddress)
            .orElseSucceed(ConversationResult.ServiceUnavailable)
            .map(_ -> updated)

    private def dispatch(
        authId: AuthId,
        conversation: ConversationRecord,
        submission: Submission,
        ipAddress: Option[String],
    ): Task[ConversationResult.Render] =
      (submission, conversation) match
        case (submitted: EmailSubmission, _) =>
          afterCredential(authId, conversation, Left(submitted.email))

        case (submitted: PhoneSubmission, _) =>
          afterCredential(authId, conversation, Right(submitted.phone))

        case (submitted: OtpSubmission, ConversationRecord.Otp(otp, _)) =>
          conversationService.checkOtp(conversation, otp, submitted.code, authId).flatMap:
            case ConversationResult.StepPassed(updated) =>
              afterFactor(authId, updated, otp.factorIndex + 1)
            case other: ConversationResult.Render =>
              ZIO.succeed(other)

        case (_: OtpResendSubmission, ConversationRecord.Otp(otp, credential)) =>
          conversationService.prepareInitialOtp(authId, conversation, credential, otp.factorIndex)

        case (submitted: PasswordSubmission, ConversationRecord.Password(password)) =>
          conversationService.checkPassword(conversation, password, submitted.password, authId).flatMap:
            case ConversationResult.StepPassed(updated) =>
              afterFactor(authId, updated, password.factorIndex + 1)
            case other: ConversationResult.Render =>
              ZIO.succeed(other)

        case (submitted: LoginPasswordSubmission, _) =>
          conversationService.checkLoginPassword(authId, conversation, submitted.login, submitted.password).flatMap:
            case ConversationResult.StepPassed(updated) =>
              afterFactor(authId, updated, nextFactorIndex = 0)
            case other: ConversationResult.Render =>
              ZIO.succeed(other)

        case (submitted: PasskeyAssertionSubmission, _) =>
          conversationService.finishPasskeyAssertion(authId, conversation, submitted.response, ipAddress)

        case (submitted: PasskeyEnrollSubmission, _) =>
          conversation.step match
            case enroll: ConversationStep.PasskeyEnroll =>
              conversationService.finishPasskeyEnroll(authId, conversation, enroll, submitted.response, submitted.name)
            case _ =>
              ZIO.succeed(ConversationResult.NotFound)

        case (_: PasskeySkipSubmission, _) =>
          conversation.step match
            case _: ConversationStep.PasskeyEnroll =>
              conversationService.skipPasskey(authId, conversation)
            case _ =>
              ZIO.succeed(ConversationResult.NotFound)

        case _ =>
          ZIO.succeed(ConversationResult.NotFound)

    /** Promote the locale the user picked in the form to the front of the conversation's preferred
      * locales, so every subsequent render keeps it. Applied in-memory before dispatch — it is
      * persisted by the same overwrite the submission already performs.
      */
    private def withUiLocale(conversation: ConversationRecord, uiLocale: Option[String]): ConversationRecord =
      uiLocale.fold(conversation): locale =>
        conversation.copy(uiLocales = Some(locale :: conversation.uiLocales.getOrElse(Nil).filterNot(_ == locale)))

    /** A factor is already satisfied when some passed factor in `conversation.amr` matches it
      * directly or counts as an equivalent (e.g. a passed passkey satisfies an otp factor).
      */
    private def isSatisfied(conversation: ConversationRecord, factor: AuthFactor): Boolean =
      PassedAuthFactor.fromFactorType(factor.`type`)
        .exists(required => conversation.amr.keySet.exists(_.satisfies(required, conversation.authFlow.equivalents)))

    /** Determine the first factor step after the user submits their credential,
      * skipping any factor types already recorded in `conversation.amr`.
      */
    private def afterCredential(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
    ): Task[ConversationResult.Render] =
      conversation.authFlow.primary.factors
        .zipWithIndex
        .dropWhile { case (factor, _) => isSatisfied(conversation, factor) }
        .headOption match
        case Some((AuthFactor(AuthFactorType.otp, _), idx)) =>
          conversationService.prepareInitialOtp(authId, conversation, credential, factorIndex = idx)

        case Some((AuthFactor(AuthFactorType.password, _), idx)) =>
          conversationService.prepareInitialPassword(authId, conversation, credential, factorIndex = idx)

        case Some((AuthFactor(AuthFactorType.passkeyEnroll, _), _)) =>
          ZIO.succeed(ConversationResult.IllegalState)

        case None =>
          conversationService.finish(authId, conversation)

    /** Determine the next step after a factor step passes, or finish if no more factors.
      * The `conversation.amr` map already includes the factor that just passed.
      * Factors whose type appears in that map are skipped.
      */
    private def afterFactor(
        authId: AuthId,
        conversation: ConversationRecord,
        nextFactorIndex: Int,
    ): Task[ConversationResult.Render] =
      conversation.authFlow.primary.factors
        .zipWithIndex
        .drop(nextFactorIndex)
        .dropWhile { case (factor, _) => isSatisfied(conversation, factor) }
        .headOption match
        case Some((AuthFactor(AuthFactorType.otp, _), idx)) =>
          conversation.credential match
            case Some(cred) => conversationService.prepareInitialOtp(authId, conversation, cred, idx)
            case None       => ZIO.succeed(ConversationResult.IllegalState)

        case Some((AuthFactor(AuthFactorType.password, _), idx)) =>
          conversationService.preparePasswordStep(authId, conversation, idx)

        case Some((AuthFactor(AuthFactorType.passkeyEnroll, _), _)) =>
          conversationService.offerPasskeyEnroll(authId, conversation)

        case None =>
          conversationService.finish(authId, conversation)
