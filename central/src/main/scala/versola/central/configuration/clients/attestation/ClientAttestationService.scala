package versola.central.configuration.clients.attestation

import versola.central.configuration.clients.{ClientId, OAuthClientService}
import zio.*

import java.sql.SQLException

/** The attestation policy of a native client (#423): what an operator sets, and what an edge is told.
  *
  * Only a client edge fronts natively has one, because `/native/start` is the only request attestation is asked on:
  * a policy for any other kind of client would be a setting nothing reads.
  */
trait ClientAttestationService:

  /** The client's policy; `off` while it has none. Refused when no such client exists. */
  def find(clientId: ClientId): Task[ClientAttestation]

  /** Stores the policy, as [[ClientAttestation.validated]] puts it. */
  def put(clientId: ClientId, attestation: ClientAttestation): Task[ClientAttestation]

  /** Back to no policy at all. */
  def delete(clientId: ClientId): Task[Unit]

  /** The policies an edge acts on: every one whose mode is not `off`. A client that has since stopped being an edge-fronted
    * native one is left to the caller to drop, as it holds the client records. */
  def active: Task[Map[ClientId, ClientAttestation]]

object ClientAttestationService:

  enum Refusal:
    /** No such client. */
    case UnknownClient

    /** The client is not one edge fronts natively. */
    case NotNative

    /** The policy is not well formed. */
    case Invalid

  case class Refused(kind: Refusal, reason: String) extends RuntimeException(reason)

  val live: URLayer[ClientAttestationRepository & OAuthClientService, ClientAttestationService] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      repository: ClientAttestationRepository,
      clients: OAuthClientService,
  ) extends ClientAttestationService:

    override def find(clientId: ClientId): Task[ClientAttestation] =
      nativeClient(clientId) *> repository.find(clientId).map(_.getOrElse(ClientAttestation.off))

    override def put(clientId: ClientId, attestation: ClientAttestation): Task[ClientAttestation] =
      for
        _ <- nativeClient(clientId)
        validated <- ZIO.fromEither(ClientAttestation.validated(attestation))
          .mapError(reason => Refused(Refusal.Invalid, reason))
        now <- Clock.instant
        // `findClient` answers from a cache that another replica's deletion reaches a moment late; the foreign key is what has the last
        // word, and the operator is told the client is gone rather than that central failed.
        _ <- repository.put(clientId, validated, now).catchSome:
          case error if isForeignKeyViolation(error) => ZIO.fail(Refused(Refusal.UnknownClient, s"client '$clientId' does not exist"))
      yield validated

    override def delete(clientId: ClientId): Task[Unit] =
      // A client that is no longer a native one still lets its policy go: refusing would leave the row for good.
      clients.findClient(clientId).someOrFail(Refused(Refusal.UnknownClient, s"client '$clientId' does not exist")) *>
        repository.delete(clientId).unit

    override def active: Task[Map[ClientId, ClientAttestation]] =
      repository.all.map(_.filter((_, attestation) => attestation.mode != AttestationMode.off))

    private def isForeignKeyViolation(error: Throwable): Boolean =
      error match
        case sql: SQLException if Option(sql.getSQLState).contains("23503") => true
        case _ => Option(error.getCause).exists(isForeignKeyViolation)

    private def nativeClient(clientId: ClientId): Task[Unit] =
      clients.findClient(clientId)
        .someOrFail(Refused(Refusal.UnknownClient, s"client '$clientId' does not exist"))
        .filterOrFail(_.isEdgeFrontedNative)(
          Refused(Refusal.NotNative, s"client '$clientId' is not a native client that authenticates with tls_client_auth"),
        )
        .unit
