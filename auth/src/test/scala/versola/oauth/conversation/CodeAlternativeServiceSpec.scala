package versola.oauth.conversation

import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{Acr, AuthFlow, PassedAuthFactor, RegistrationCredential, RegistrationFlow}
import versola.oauth.conversation.CodeAlternativeFixtures.*
import versola.oauth.conversation.model.{CodeAlternative, ConversationRecord, ConversationStep}
import versola.util.UnitSpecBase
import zio.ZIO
import zio.prelude.NonEmptyList
import zio.test.*

object CodeAlternativeServiceSpec extends UnitSpecBase:

  /** A user with a passkey, and a tenant that has passkey settings and no class of authentication of its own. */
  class Env(hasPasskey: Boolean = true, settings: Boolean = true, vocabulary: Map[Acr, NonEmptyList[PassedAuthFactor]] = Map.empty):
    val passkeyRepository = stub[PasskeyRepository]
    val configService = stub[OAuthConfigurationService]
    val service = CodeAlternativeService.Impl(passkeyRepository, configService)

    def given_ =
      for
        _ <- passkeyRepository.listByUser.succeedsWith(if hasPasskey then Vector(aPasskey) else Vector.empty)
        _ <- configService.getPasskeySettings.succeedsWith(Option.when(settings)(passkeySettings))
        _ <- configService.getAcrVocabulary.succeedsWith(vocabulary)
      yield ()

  private def offered(env: Env, record: ConversationRecord = onCode, switched: Int = 0) =
    env.given_ *> env.service.available(record, switched)

  val spec = suite("CodeAlternativeService")(
    suite("the passkey")(
      test("is offered to a user who has one, when the flow can ask for one and the conversation has not passed one") {
        for offered <- offered(Env())
        yield assertTrue(offered == List(CodeAlternative.Passkey))
      },
      test("is not offered to a user who has none") {
        for offered <- offered(Env(hasPasskey = false))
        yield assertTrue(offered.isEmpty)
      },
      test("is not offered when the conversation identifies no user, as a number that has no account does not") {
        val env = Env()
        for
          offered <- offered(env, onCode.copy(userId = None))
        yield assertTrue(offered.isEmpty, env.passkeyRepository.listByUser.calls.isEmpty)
      },
      test("is not offered to a registration: what the person has proved so far is only the number") {
        val registering = onCode.copy(
          userId = None,
          registrationFlow = Some(RegistrationFlow(credential = RegistrationCredential.phone, steps = Nil, roleIds = Set.empty)),
          registrationStep = Some(0),
        )
        // Even where an account was made halfway through (a registration that has a user), it is a registration.
        val registered = registering.copy(userId = Some(userId))
        for
          before <- offered(Env(), registering)
          after <- offered(Env(), registered)
        yield assertTrue(before.isEmpty, after.isEmpty)
      },
      test("is not offered when the client's flow cannot ask for a passkey, whatever the user has") {
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
      test("is not offered once the conversation has gone to an alternative as often as it may") {
        for
          last <- offered(Env(), switched = ConversationStep.Otp.MaxSwitches - 1)
          over <- offered(Env(), switched = ConversationStep.Otp.MaxSwitches)
        yield assertTrue(last == List(CodeAlternative.Passkey), over.isEmpty)
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
        // Fixed as the code alone (the user had no passkey then, and so none could stand in), and a passkey added since.
        val fixed = onCode.copy(targetAcr = Some(sensitive), targetFactors = Some(List(PassedAuthFactor.otp)))
        for offered <- offered(Env(), fixed)
        yield assertTrue(offered.isEmpty)
      },
      test("is worked out again each time: a passkey added meanwhile is offered the next time, and one removed is not") {
        val env = Env()
        for
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          before <- env.service.available(onCode, 0)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector(aPasskey))
          added <- env.service.available(onCode, 0)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          removed <- env.service.available(onCode, 0)
        yield assertTrue(before.isEmpty, added == List(CodeAlternative.Passkey), removed.isEmpty)
      },
      test("is not offered when the lookup cannot be completed: nothing is added to the code that could not be checked") {
        val env = Env()
        for
          _ <- env.passkeyRepository.listByUser.failsWith(new RuntimeException("database is down"))
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          offered <- env.service.available(onCode, 0)
        yield assertTrue(offered.isEmpty)
      },
      test("is the same for a conversation on its code step whatever became of the send: the answer does not look at the step") {
        // The page of a faked or refused send and the page of a real one are told apart by nothing here.
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
