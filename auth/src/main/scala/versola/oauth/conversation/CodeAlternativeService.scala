package versola.oauth.conversation

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.PassedAuthFactor
import versola.oauth.conversation.model.{CodeAlternative, ConversationRecord, ConversationStep}
import zio.{UIO, ZIO, ZLayer}

/** What may be offered in place of the one-time code, for a conversation that is on (or about to be put on) its code step.
  *
  * Asked again each time the code page is drawn and each time one is chosen: a choice that comes from the client is only a
  * request for what this says is available now.
  *
  * What is offered **does not depend on who the number belongs to**: not on whether it is a user's, a registration's or
  * nobody's, and not on what that user has enrolled. The page of a number that has an account with a passkey, of one that
  * has an account without, and of one that has none must be the same page, or the page tells whoever tries numbers which of
  * them are accounts. Nothing here reads the user, and the ceremony the choice starts is the same for all of them too (see
  * [[ConversationService.startPasskeyAssertion]]).
  */
trait CodeAlternativeService:
  /** `switched` is how many times the conversation has already left its code step for an alternative. */
  def available(conversation: ConversationRecord, switched: Int): UIO[List[CodeAlternative]]

object CodeAlternativeService:
  def live = ZLayer.fromFunction(Impl(_))

  class Impl(configService: OAuthConfigurationService) extends CodeAlternativeService:

    override def available(conversation: ConversationRecord, switched: Int): UIO[List[CodeAlternative]] =
      passkey(conversation, switched).map(offered => if offered then List(CodeAlternative.Passkey) else Nil)

    /** A passkey is offered in place of the code when it is at least as strong a proof of the step and the conversation can
      * ask for one, i.e. when every one of these holds:
      *
      *   - the client's authentication flow can ask for a passkey, and the tenant has passkey settings: the same condition
      *     under which `sensitive` (see [[versola.oauth.authorize.AcrResolutionService]]) can be satisfied by one, so a tenant
      *     that did not enable passkeys for this client is not given them by the code page;
      *   - the conversation has not passed a passkey already: a passkey that was passed is not asked for twice, and a
      *     conversation that needs one has been put on the passkey step ahead of the code;
      *   - the class of authentication being pursued does not ask for the code itself (`otp` among its factors) unless the
      *     flow says that a passkey counts as one (`equivalents`): the passkey is stronger, but what a tenant wrote as the
      *     requirement is the tenant's to relax, and a conversation that passed with a passkey in place of a code the class
      *     names would end at the access page, having asked the person for something that could not work;
      *   - the conversation has not already switched too often.
      *
      * Whether there is a user, and whether they have a passkey, is not asked, and the answer is the same for a number that
      * belongs to nobody (see the trait). Choosing a passkey where there is none fails at the ceremony, closed: a passkey
      * of the user the conversation identifies is the only one it accepts.
      */
    private def passkey(conversation: ConversationRecord, switched: Int): UIO[Boolean] =
      val flow = conversation.authFlow
      val cheap =
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
        yield settings.isDefined && covered
