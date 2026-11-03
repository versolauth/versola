package versola.oauth.conversation

import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.PassedAuthFactor
import versola.oauth.conversation.model.{CodeAlternative, ConversationRecord, ConversationStep}
import zio.{Task, UIO, ZIO, ZLayer}

/** What may be offered in place of the one-time code, for a conversation that is on (or about to be put on) its code step.
  *
  * Asked again each time the code page is drawn and each time one is chosen: the answer depends on what the user has
  * enrolled and what the conversation has passed, both of which move, and a choice that comes from the client is only a
  * request for what this says is available now.
  */
trait CodeAlternativeService:
  /** `switched` is how many times the conversation has already left its code step for an alternative. A check that cannot
    * be completed offers nothing: an alternative is only ever added to the code, never a way around it. */
  def available(conversation: ConversationRecord, switched: Int): UIO[List[CodeAlternative]]

object CodeAlternativeService:
  def live = ZLayer.fromFunction(Impl(_, _))

  class Impl(passkeyRepository: PasskeyRepository, configService: OAuthConfigurationService) extends CodeAlternativeService:

    override def available(conversation: ConversationRecord, switched: Int): UIO[List[CodeAlternative]] =
      passkey(conversation, switched)
        .catchAllCause(cause => ZIO.logWarningCause("could not tell which alternatives to the code to offer; none is", cause).as(false))
        .map(offered => if offered then List(CodeAlternative.Passkey) else Nil)

    /** A passkey stands in for the code when it is at least as strong a proof of the step and the conversation can ask for
      * one, i.e. when every one of these holds:
      *
      *   - the conversation identifies a user: a phone or an address that has an account. A registration has none, and what
      *     a person who is registering has proved so far is only the number;
      *   - the client's flow can ask for a passkey, and the tenant has passkey settings: the same condition under which
      *     `sensitive` (see [[versola.oauth.authorize.AcrResolutionService]]) can be satisfied by one, so a tenant that did
      *     not enable passkeys for this client is not given them by the code page;
      *   - the user has one: nothing is offered that could not be completed;
      *   - the conversation has not passed a passkey already: a passkey that was passed is not asked for twice, and a
      *     conversation that needs one has been put on the passkey step ahead of the code;
      *   - the class of authentication being pursued does not ask for the code itself (`otp` among its factors) unless
      *     the flow says that a passkey counts as one (`equivalents`): the passkey is stronger, but what a tenant wrote as
      *     the requirement is the tenant's to relax, and a conversation that passed with a passkey in place of a code
      *     the class names would end at the access page, having asked the person for something that could not work;
      *   - the conversation has not already switched too often.
      */
    private def passkey(conversation: ConversationRecord, switched: Int): Task[Boolean] =
      val flow = conversation.authFlow
      val cheap =
        conversation.userId.isDefined &&
          !conversation.isRegistering &&
          flow.passkey.isDefined &&
          switched < ConversationStep.Otp.MaxSwitches &&
          !conversation.amr.keySet.exists(_.satisfies(PassedAuthFactor.passkey, flow.equivalents))
      if !cheap then ZIO.succeed(false)
      else
        for
          settings <- configService.getPasskeySettings(conversation.clientId)
          vocabulary <- configService.getAcrVocabulary(conversation.clientId)
          // What is asked of this conversation is the factors fixed when it started, else the tenant's definition of the class.
          asksForCode = conversation.requiredFactors(vocabulary).exists(_.contains(PassedAuthFactor.otp))
          covered = !asksForCode || PassedAuthFactor.passkey.satisfies(PassedAuthFactor.otp, flow.equivalents)
          has <-
            if settings.isDefined && covered then conversation.userId.fold(ZIO.succeed(false))(passkeyRepository.listByUser(_).map(_.nonEmpty))
            else ZIO.succeed(false)
        yield has
