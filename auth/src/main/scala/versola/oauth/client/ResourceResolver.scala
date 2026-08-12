package versola.oauth.client

import versola.oauth.client.model.{OAuthClientRecord, ResourceUri}
import zio.{IO, ZIO}

object ResourceResolver:
  val EdgeResource: ResourceUri = ResourceUri("resource://edge")

  def resolve(
      configurationService: OAuthConfigurationService,
      client: OAuthClientRecord,
      requested: Option[List[ResourceUri]],
  ): IO[ResourceUri, List[ResourceUri]] =
    requested match
      case None =>
        configurationService.getResourcesForClient(client.tenantId, client.id)
          .map(_.filterNot(_.internal).map(_.resource).appended(EdgeResource).distinct)
      case Some(resources) if hasEdgeWithInternalResource(resources) =>
        ZIO.fail(EdgeResource)
      case Some(resources) =>
        ZIO.foreach(resources): resource =>
          if resource == EdgeResource then ZIO.succeed(resource)
          else
            val registered = ResourceUri.internalResourceId(resource) match
              case Some(resourceId) =>
                configurationService.findResourceById(client.tenantId, resourceId).map(_.filter(_.internal))
              case None =>
                configurationService.findResource(client.tenantId, resource).map(_.filter(!_.internal))
            registered
              .map(_.filter(_.audience.contains(client.id)))
              .someOrFail(resource)
              .as(resource)
        .map(_.distinct)

  private def hasEdgeWithInternalResource(resources: List[ResourceUri]): Boolean =
    resources.contains(EdgeResource) &&
      resources.exists(resource =>
        resource != EdgeResource && ResourceUri.internalResourceId(resource).isDefined,
      )