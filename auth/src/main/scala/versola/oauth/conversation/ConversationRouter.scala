package versola.oauth.conversation

import versola.oauth.client.model.{AuthFactor, AuthFactorType}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.util.{Email, Phone, SecureRandom}
import zio.{Task, ZIO, ZLayer}

trait ConversationRouter:
  def getConversation(authId: AuthId): Task[Option[ConversationRecord]]

  def submit(authId: AuthId, submission: Submission): Task[ConversationResult.Render]

object ConversationRouter:
  def live = ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      conversationRepository: ConversationRepository,
      conversationService: ConversationService,
      secureRandom: SecureRandom,
  ) extends ConversationRouter:

    override def getConversation(authId: AuthId): Task[Option[ConversationRecord]] =
      conversationService.find(authId)

    override def submit(
        authId: AuthId,
        submission: Submission,
    ): Task[ConversationResult.Render] =
      conversationService.find(authId)
        .map(_.map(submission -> _))
        .flatMap:
          case None =>
            ZIO.succeed(ConversationResult.NotFound)

          case Some((submitted: EmailSubmission, conversation)) =>
            afterCredential(authId, conversation, Left(submitted.email))

          case Some((submitted: PhoneSubmission, conversation)) =>
            afterCredential(authId, conversation, Right(submitted.phone))

          case Some((submitted: OtpSubmission, conversation @ ConversationRecord.Otp(otp, _))) =>
            conversationService.checkOtp(conversation, otp, submitted.code, authId).flatMap:
              case _: ConversationResult.StepPassed =>
                afterFactor(authId, conversation, otp.factorIndex + 1)
              case other: ConversationResult.Render =>
                ZIO.succeed(other)

          case Some((submitted: OtpResendSubmission, conversation @ ConversationRecord.Otp(otp, credential))) =>
            conversationService.prepareInitialOtp(authId, conversation, credential, otp.factorIndex)

          case Some((submitted: PasswordSubmission, conversation @ ConversationRecord.Password(password))) =>
            conversationService.checkPassword(conversation, password, submitted.password, authId).flatMap:
              case _: ConversationResult.StepPassed =>
                afterFactor(authId, conversation, password.factorIndex + 1)
              case other: ConversationResult.Render =>
                ZIO.succeed(other)

          case _ =>
            ZIO.succeed(ConversationResult.NotFound)

    /** Determine the first factor step after the user submits their credential. */
    private def afterCredential(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
    ): Task[ConversationResult.Render] =
      conversation.authFlow.primary.factors.headOption match
        case Some(AuthFactor(AuthFactorType.otp, _)) =>
          conversationService.prepareInitialOtp(authId, conversation, credential, factorIndex = 0)
        case Some(AuthFactor(AuthFactorType.password, _)) =>
          conversationService.prepareInitialPassword(authId, conversation, credential, factorIndex = 0)
        case None =>
          ZIO.succeed(ConversationResult.IllegalState)

    /** Determine the next step after a factor step passes, or finish if no more factors. */
    private def afterFactor(
        authId: AuthId,
        conversation: ConversationRecord,
        nextFactorIndex: Int,
    ): Task[ConversationResult.Render] =
      conversation.authFlow.primary.factors.lift(nextFactorIndex) match
        case Some(AuthFactor(AuthFactorType.otp, _)) =>
          conversation.credential match
            case Some(cred) => conversationService.prepareInitialOtp(authId, conversation, cred, nextFactorIndex)
            case None       => ZIO.succeed(ConversationResult.IllegalState)
        case Some(AuthFactor(AuthFactorType.password, _)) =>
          conversationService.preparePasswordStep(authId, conversation, nextFactorIndex)
        case None =>
          conversationService.finish(authId, conversation)
