package versola.oauth.identifier

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.user.UserRepository
import versola.user.model.UserId
import zio.{IO, ZIO, ZLayer, durationInt}

enum IdentifierChangeIntentError:
  /** The value is not a valid identifier of that kind, or the tenant does not allow it. */
  case InvalidValue

  /** Some user, this one included, already holds the identifier. */
  case AlreadyInUse

  case UserNotFound

/** Records that a signed-in user asked, on the account page, to change an identifier. Without this
  * record `/authorize` refuses the reserved `*-change` ACR values, so a client cannot start the
  * flow on its own.
  */
trait IdentifierChangeIntentService:
  def create(
      userId: UserId,
      clientId: ClientId,
      kind: IdentifierChangeKind,
      value: String,
  ): IO[IdentifierChangeIntentError | Throwable, Unit]

object IdentifierChangeIntentService:
  /** Long enough for the redirect to `/authorize` that follows; the intent is not a session. */
  private val IntentTtl = 5.minutes

  def live: ZLayer[IdentifierChangeIntentRepository & UserRepository & OAuthConfigurationService, Nothing, IdentifierChangeIntentService] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      intents: IdentifierChangeIntentRepository,
      userRepository: UserRepository,
      configuration: OAuthConfigurationService,
  ) extends IdentifierChangeIntentService:
    override def create(
        userId: UserId,
        clientId: ClientId,
        kind: IdentifierChangeKind,
        value: String,
    ): IO[IdentifierChangeIntentError | Throwable, Unit] =
      for
        prefixes <- kind match
          case IdentifierChangeKind.Phone => configuration.getAllowedPhonePrefixes(clientId)
          case IdentifierChangeKind.Email => ZIO.succeed(List.empty[String])
        credential <- ZIO.fromOption(IdentifierChangeCredentials.parse(kind, value, prefixes))
          .orElseFail(IdentifierChangeIntentError.InvalidValue)
        _ <- userRepository.find(userId).someOrFail(IdentifierChangeIntentError.UserNotFound)
        // Asked here, before the browser is sent through /authorize, so the user hears about a taken
        // identifier on the page rather than after proving the old one and typing a code.
        holder <- userRepository.findByCredential(credential)
        _ <- ZIO.fail(IdentifierChangeIntentError.AlreadyInUse).when(holder.isDefined)
        _ <- intents.create(userId, clientId, kind, credential, IntentTtl)
      yield ()
