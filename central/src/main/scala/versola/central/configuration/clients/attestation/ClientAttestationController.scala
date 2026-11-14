package versola.central.configuration.clients.attestation

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.configuration.clients.ClientId
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeBasic}
import zio.*
import zio.http.*
import zio.json.EncoderOps

/** The operator's endpoints for a native client's attestation policy (#423): read it, set it, take it away. Edge reads it
  * from the client sync, not from here. */
object ClientAttestationController extends AuditedController:
  type Env = Tracing & ClientAttestationService & ResourceService & CentralConfig & EdgeService

  def endpoints = List(
    getEndpoint,
    putEndpoint,
    deleteEndpoint,
  )

  /** The client's policy, `off` over no builds while it has none. */
  val getEndpoint =
    (Method.GET / "configuration" / "clients" / "attestation").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        clientId <- request.url.queryZIO[ClientId]("clientId")
        response <- ZIO.serviceWithZIO[ClientAttestationService](_.find(clientId))
          .map(attestation => Response.json(attestation.toJson))
          .catchSome(refused)
      yield response
    }

  val putEndpoint =
    (Method.PUT / "configuration" / "clients" / "attestation").audited(Audit.change(
      "client-attestation",
      Audit.update,
      Some("client"),
    )) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        clientId <- request.url.queryZIO[ClientId]("clientId")
        _ <- Audit.target(clientId)
        body <- request.bodyAs[ClientAttestation]
        response <- ZIO.serviceWithZIO[ClientAttestationService](_.put(clientId, body))
          .map(stored => Response.json(stored.toJson))
          .catchSome(refused)
      yield response
    }

  val deleteEndpoint =
    (Method.DELETE / "configuration" / "clients" / "attestation").audited(Audit.change(
      "client-attestation",
      Audit.delete,
      Some("client"),
    )) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        clientId <- request.url.queryZIO[ClientId]("clientId")
        _ <- Audit.target(clientId)
        response <- ZIO.serviceWithZIO[ClientAttestationService](_.delete(clientId))
          .as(Response.status(Status.NoContent))
          .catchSome(refused)
      yield response
    }

  /** Every refusal is the operator's to act on, and says what to change. */
  private val refused: PartialFunction[Throwable, UIO[Response]] =
    case error: ClientAttestationService.Refused =>
      ZIO.succeed(Response.text(error.getMessage).status(error.kind match
        case ClientAttestationService.Refusal.UnknownClient => Status.NotFound
        case ClientAttestationService.Refusal.NotNative => Status.UnprocessableEntity
        case ClientAttestationService.Refusal.Invalid => Status.BadRequest))
