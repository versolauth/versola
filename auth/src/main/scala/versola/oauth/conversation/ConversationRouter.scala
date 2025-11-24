package versola.oauth.conversation

import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.security.SecureRandom
import zio.{Task, UIO, ZIO, ZLayer}

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

          case Some((submitted: PhoneSubmission, conversation)) =>
            conversationService.prepareInitialOtp(authId, conversation, Right(submitted.phone))

          case Some((submitted: EmailSubmission, conversation)) =>
            conversationService.prepareInitialOtp(authId, conversation, Left(submitted.email))

          case Some((submitted: OtpSubmission, conversation @ ConversationRecord.Otp(otp, credential))) =>
            conversationService.checkOtp(conversation, otp, submitted.code, authId).flatMap:
              case result: ConversationResult.StepPassed =>
                conversationService.finish(authId, conversation)

              case other: ConversationResult.Render =>
                ZIO.succeed(other)

          case Some((submitted: OtpResendSubmission, conversation @ ConversationRecord.Otp(otp, credential))) =>
            conversationService.prepareInitialOtp(authId, conversation, Left(???))

          case _ =>
            ZIO.succeed(ConversationResult.NotFound)
