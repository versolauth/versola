package versola.oauth.client

import versola.oauth.client.model.{AuthorizationDetail, OAuthClientRecord, ResourceUri}
import versola.util.JsonSchemaValidator
import zio.{IO, UIO, ZIO}

/** Validates requested RFC 9396 `authorization_details` against the tenant's type registry.
  *
  * Per §5 the AS rejects a request whose details use an unknown type, contain unknown fields
  * for a known type, or otherwise do not match the type's definition; the definition is the
  * JSON Schema registered in central. `locations`, when present, are resolved against the
  * resource registry exactly like the RFC 8707 `resource` parameter, so a client cannot ask
  * for access at a resource it is not registered for.
  */
object AuthorizationDetailResolver:
  /** The offending detail (its `type`) and why it was rejected. */
  case class Rejected(`type`: String, reason: String)

  def resolve(
      configurationService: OAuthConfigurationService,
      schemaValidator: JsonSchemaValidator,
      client: OAuthClientRecord,
      details: List[AuthorizationDetail],
  ): IO[Rejected, List[AuthorizationDetail]] =
    ZIO.foreach(details)(detail => resolveOne(configurationService, schemaValidator, client, detail))

  private def resolveOne(
      configurationService: OAuthConfigurationService,
      schemaValidator: JsonSchemaValidator,
      client: OAuthClientRecord,
      detail: AuthorizationDetail,
  ): IO[Rejected, AuthorizationDetail] =
    for
      registered <- configurationService.findAuthorizationDetailType(client.tenantId, detail.`type`)
        .someOrFail(Rejected(detail.`type`, "unknown authorization details type"))
      errors <- schemaValidator.validate(registered.schema, detail.value)
      _ <- ZIO.when(errors.nonEmpty)(ZIO.fail(Rejected(detail.`type`, errors.mkString("; "))))
      _ <- validateLocations(configurationService, client, detail)
    yield detail

  private def validateLocations(
      configurationService: OAuthConfigurationService,
      client: OAuthClientRecord,
      detail: AuthorizationDetail,
  ): IO[Rejected, Unit] =
    ZIO.when(detail.locations.nonEmpty):
      ResourceResolver.resolve(configurationService, client, Some(detail.locations))
        .mapError(resource => Rejected(detail.`type`, s"unknown location - $resource"))
    .unit
