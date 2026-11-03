package versola.oauth.conversation

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{Acr, AuthFlow, PassedAuthFactor, RegistrationCredential, RegistrationFlow}
import versola.oauth.conversation.CodeAlternativeFixtures.*
import versola.oauth.conversation.model.{CodeAlternative, ConversationRecord}
import versola.util.UnitSpecBase
import zio.prelude.NonEmptyList
import zio.test.*

object CodeAlternativeServiceSpec extends UnitSpecBase:

  /** A tenant that has passkey settings and no class of authentication of its own. */
  class Env(settings: Boolean = true, vocabulary: Map[Acr, NonEmptyList[PassedAuthFactor]] = Map.empty):
    val configService = stub[OAuthConfigurationService]
    val service = CodeAlternativeService.Impl(configService)

    def given_ =
      for
        _ <- configService.getPasskeySettings.succeedsWith(Option.when(settings)(passkeySettings))
        _ <- configService.getAcrVocabulary.succeedsWith(vocabulary)
      yield ()

  private def offered(env: Env, record: ConversationRecord = onCode) =
    env.given_ *> env.service.available(record)

  private val registering = onCode.copy(
    userId = None,
    registrationFlow = Some(RegistrationFlow(credential = RegistrationCredential.phone, steps = Nil, roleIds = Set.empty)),
    registrationStep = Some(0),
  )

  val spec = suite("CodeAlternativeService")(
    suite("the passkey")(
      test("is offered when the flow can ask for one, the tenant has passkey settings and the conversation has not passed one") {
        for offered <- offered(Env())
        yield assertTrue(offered == List(CodeAlternative.Passkey))
      },
      test("is offered the same whoever the number belongs to: a user, a registration, nobody") {
        // What is offered must not tell which numbers are accounts, so there is nothing here to tell them apart by.
        for
          user <- offered(Env(), onCode)
          nobody <- offered(Env(), onCode.copy(userId = None, userPhone = None))
          registration <- offered(Env(), registering)
          registered <- offered(Env(), registering.copy(userId = Some(userId)))
        yield assertTrue(
          user == List(CodeAlternative.Passkey),
          nobody == user,
          registration == user,
          registered == user,
        )
      },
      test("does not look at the user at all: what they have enrolled is not read") {
        // The service has no repository to read it from; the only lookups are the tenant's configuration.
        val env = Env()
        for _ <- offered(env)
        yield assertTrue(env.configService.getPasskeySettings.calls.size == 1)
      },
      test("is not offered when the client's flow cannot ask for a passkey") {
        for offered <- offered(Env(), onCode.copy(authFlow = AuthFlow.default))
        yield assertTrue(offered.isEmpty)
      },
      test("is not offered when the tenant has no passkey settings") {
        for offered <- offered(Env(settings = false))
        yield assertTrue(offered.isEmpty)
      },
      test("is not offered when the conversation has passed a passkey already") {
        for offered <- offered(Env(), onCode.copy(amr = Map(passedPasskey())))
        yield assertTrue(offered.isEmpty)
      },
      test("is not offered when what the conversation pursues asks for the code itself and a passkey is not said to count as one") {
        val mfa = Acr("mfa")
        val env = Env(vocabulary = Map(mfa -> NonEmptyList(PassedAuthFactor.otp)))
        for offered <- offered(env, onCode.copy(targetAcr = Some(mfa)))
        yield assertTrue(offered.isEmpty)
      },
      test("is offered where what is pursued asks for the code and the flow says a passkey counts as one") {
        val mfa = Acr("mfa")
        val env = Env(vocabulary = Map(mfa -> NonEmptyList(PassedAuthFactor.otp)))
        for offered <- offered(env, onCode.copy(targetAcr = Some(mfa), authFlow = flowWherePasskeyIsTheCode))
        yield assertTrue(offered == List(CodeAlternative.Passkey))
      },
      test("is offered where what is pursued does not ask for the code, as a class that asks for a password and a passkey") {
        val strong = Acr("strong")
        val env = Env(vocabulary = Map(strong -> NonEmptyList(PassedAuthFactor.password)))
        for offered <- offered(env, onCode.copy(targetAcr = Some(strong)))
        yield assertTrue(offered == List(CodeAlternative.Passkey))
      },
      test("is judged by the factors fixed when the conversation started, not by a tenant's later vocabulary") {
        val sensitive = Acr("sensitive")
        val fixed = onCode.copy(targetAcr = Some(sensitive), targetFactors = Some(List(PassedAuthFactor.otp)))
        for offered <- offered(Env(), fixed)
        yield assertTrue(offered.isEmpty)
      },
      test("is the same for a conversation on its code step whatever became of the send: the answer does not look at the step") {
        for
          sent <- offered(Env(), onCode.copy(step = sentCode))
          faked <- offered(Env(), onCode.copy(step = fakeCode))
          pending <- offered(Env(), onCode.copy(step = sentCode.copy(real = None, timesRequested = 0)))
        yield assertTrue(sent == List(CodeAlternative.Passkey), faked == sent, pending == sent)
      },
    ),
    suite("the wire")(
      test("a choice is parsed by the name it has on the page, and one that is not offered by this server is refused") {
        assertTrue(
          CodeAlternative.parse("passkey") == Right(CodeAlternative.Passkey),
          CodeAlternative.parse("Passkey").isLeft,
          CodeAlternative.parse("telegram").isLeft,
          CodeAlternative.parse("").isLeft,
        )
      },
    ),
  )
