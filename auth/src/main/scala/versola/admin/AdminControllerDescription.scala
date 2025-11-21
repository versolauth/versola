package versola.admin

import versola.oauth.model.{ClientId, ScopeToken}
import zio.http.codec.HttpContentCodec
import zio.http.endpoint.Endpoint
import zio.http.{RoutePattern, Status}
import zio.prelude.NonEmptySet
import zio.schema.*

private[admin] object AdminControllerDescription:

  // Endpoint definitions
  val createClientEndpoint =
    Endpoint(RoutePattern.POST / "api" / "v1" / "admin" / "oauth" / "clients")
      .in[CreateClientRequest]
      .out[CreateClientResponse]
      .outError[Throwable](Status.InternalServerError)

  val rotateSecretEndpoint =
    Endpoint(RoutePattern.POST / "api" / "v1" / "admin" / "oauth" / "clients" / "rotate-secret")
      .in[RotateSecretRequest]
      .out[RotateSecretResponse]
      .outError[Throwable](Status.InternalServerError)

  val deleteClientsEndpoint =
    Endpoint(RoutePattern.POST / "api" / "v1" / "admin" / "oauth" / "clients" / "delete")
      .in[DeleteClientsRequest]
      .out[Unit]
      .outError[Throwable](Status.InternalServerError)

  val deletePreviousSecretEndpoint =
    Endpoint(RoutePattern.POST / "api" / "v1" / "admin" / "oauth" / "clients" / "delete-previous-secret")
      .in[DeletePreviousSecretRequest]
      .out[Unit]
      .outError[Throwable](Status.InternalServerError)

  val getAllDataEndpoint =
    Endpoint(RoutePattern.GET / "api" / "v1" / "admin" / "oauth" / "all")
      .out[AllDataResponse]
      .outError[Throwable](Status.InternalServerError)

  val createScopesEndpoint =
    Endpoint(RoutePattern.POST / "api" / "v1" / "admin" / "oauth" / "scopes")
      .in[CreateScopesRequest]
      .out[Unit]
      .outError[Throwable](Status.InternalServerError)

  val deleteScopesEndpoint =
    Endpoint(RoutePattern.POST / "api" / "v1" / "admin" / "oauth" / "scopes" / "delete")
      .in[DeleteScopesRequest]
      .out[Unit]
      .outError[Throwable](Status.InternalServerError)

  // Request/Response models
  case class CreateClientRequest(
      id: ClientId,
      clientName: String,
      redirectUris: NonEmptySet[String],
      allowedScopes: Set[String],
  ) derives Schema

  case class CreateClientResponse(
      secret: String,
  ) derives Schema

  case class RotateSecretRequest(
      clientId: String,
  ) derives Schema

  case class RotateSecretResponse(
      newSecret: String,
  ) derives Schema

  case class DeleteClientsRequest(
      clientIds: List[ClientId],
  ) derives Schema

  case class DeletePreviousSecretRequest(
      clientId: String,
  ) derives Schema

  case class CreateScopesRequest(scopes: Set[OneScope]) derives Schema

  case class OneScope(
      name: String,
      description: String,
      claims: Set[String],
  ) derives Schema

  case class AllDataResponse(
      clients: Vector[OauthClientResponse],
      scopes: Vector[OneScope],
  ) derives Schema

  case class DeleteScopesRequest(
      scopeNames: Set[String],
  ) derives Schema

  case class OauthClientResponse(
      id: String,
      clientName: String,
      redirectUris: Set[String],
      scope: Set[String],
      hasPreviousSecret: Boolean,
  ) derives Schema

  // HttpContentCodec instances (copied from Controller)
  def emptyJsonCodec[A]: HttpContentCodec[A] = HttpContentCodec.json
    .only[A](using
      Schema.fail("No schema"),
    )

  given HttpContentCodec[Throwable] = emptyJsonCodec[Throwable]
  given HttpContentCodec[Unit] = emptyJsonCodec[Unit]
