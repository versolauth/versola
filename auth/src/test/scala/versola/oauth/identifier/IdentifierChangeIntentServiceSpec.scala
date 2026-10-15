package versola.oauth.identifier

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, PhoneRegion}
import versola.user.model.{UserId, UserRecord}
import versola.user.{UserIntent, UserIntentRepository, UserRepository}
import versola.util.{Email, Phone, UnitSpecBase}
import zio.*
import zio.test.*

import java.util.UUID

object IdentifierChangeIntentServiceSpec extends UnitSpecBase:

  private val userId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val clientId = ClientId("test-client")
  private val currentPhone = Phone("+77011234567")
  private val currentEmail = Email("me@example.com")
  private val user = UserRecord.empty(userId).copy(email = Some(currentEmail), phone = Some(currentPhone))

  private val usRegion = PhoneRegion("US", "+1", 10, "\\d{10}", false)
  private val kzRegion = PhoneRegion("KZ", "+7", 10, "7\\d{9}", false)

  class Env:
    val intents = stub[UserIntentRepository]
    val userRepository = stub[UserRepository]
    val configuration = stub[OAuthConfigurationService]
    val service = IdentifierChangeIntentService.Impl(intents, userRepository, configuration)

  val spec = suite("IdentifierChangeIntentService")(
    test("records an intent for the new phone, for five minutes") {
      val env = Env()
      for
        _ <- env.configuration.getPhoneRegions.succeedsWith(Nil)
        _ <- env.userRepository.find.succeedsWith(Some(user))
        _ <- env.userRepository.findByCredential.succeedsWith(None)
        _ <- env.intents.create.succeedsWith(())
        _ <- env.service.create(userId, clientId, IdentifierChangeKind.Phone, "+77011234568")
        calls = env.intents.create.calls
      yield assertTrue(calls == List((userId, clientId, UserIntent.ChangePhone(Phone("+77011234568")), 5.minutes)))
    },
    test("records an intent for a new email without asking for phone prefixes") {
      val env = Env()
      for
        _ <- env.userRepository.find.succeedsWith(Some(user))
        _ <- env.userRepository.findByCredential.succeedsWith(None)
        _ <- env.intents.create.succeedsWith(())
        _ <- env.service.create(userId, clientId, IdentifierChangeKind.Email, "new@example.com")
        calls = env.intents.create.calls
        prefixLookups = env.configuration.getPhoneRegions.calls
      yield assertTrue(
        calls == List((userId, clientId, UserIntent.ChangeEmail(Email("new@example.com")), 5.minutes)),
        prefixLookups.isEmpty,
      )
    },
    test("refuses a value that is not an identifier of the kind, or that the tenant does not allow") {
      val env = Env()
      for
        _ <- env.configuration.getPhoneRegions.succeedsWith(List(kzRegion))
        garbage <- env.service.create(userId, clientId, IdentifierChangeKind.Phone, "not-a-phone").flip
        foreign <- env.service.create(userId, clientId, IdentifierChangeKind.Phone, "+12025551234").flip
        email <- env.service.create(userId, clientId, IdentifierChangeKind.Email, "+77011234567").flip
        created = env.intents.create.calls
      yield assertTrue(
        garbage == IdentifierChangeIntentError.InvalidValue,
        foreign == IdentifierChangeIntentError.InvalidValue,
        email == IdentifierChangeIntentError.InvalidValue,
        created.isEmpty,
      )
    },
    test("refuses an identifier that is already in use, the user's own or another's, before anything is recorded") {
      val env = Env()
      for
        _ <- env.configuration.getPhoneRegions.succeedsWith(Nil)
        _ <- env.userRepository.find.succeedsWith(Some(user))
        _ <- env.userRepository.findByCredential.succeedsWith(Some(UserRecord.empty(UserId(UUID.randomUUID()))))
        phone <- env.service.create(userId, clientId, IdentifierChangeKind.Phone, currentPhone).flip
        email <- env.service.create(userId, clientId, IdentifierChangeKind.Email, "taken@example.com").flip
        created = env.intents.create.calls
      yield assertTrue(
        phone == IdentifierChangeIntentError.AlreadyInUse,
        email == IdentifierChangeIntentError.AlreadyInUse,
        created.isEmpty,
      )
    },
    test("refuses an unknown user") {
      val env = Env()
      for
        _ <- env.configuration.getPhoneRegions.succeedsWith(Nil)
        _ <- env.userRepository.find.succeedsWith(None)
        error <- env.service.create(userId, clientId, IdentifierChangeKind.Phone, "+77011234568").flip
        created = env.intents.create.calls
      yield assertTrue(error == IdentifierChangeIntentError.UserNotFound, created.isEmpty)
    },
    test("a user without any phone can add one") {
      val env = Env()
      for
        _ <- env.configuration.getPhoneRegions.succeedsWith(Nil)
        _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(userId).copy(email = Some(currentEmail))))
        _ <- env.userRepository.findByCredential.succeedsWith(None)
        _ <- env.intents.create.succeedsWith(())
        _ <- env.service.create(userId, clientId, IdentifierChangeKind.Phone, "+77011234568")
        created = env.intents.create.calls
      yield assertTrue(created.length == 1)
    },
  )
