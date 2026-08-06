package versola.oauth.client.model

/** The subset of a central resource that auth needs to validate the RFC 8707 `resource`
  * request parameter: its id (used as the `aud` value) and which tenant it belongs to.
  */
case class ResourceRecord(
    resourceId: ResourceId,
    tenantId: TenantId,
    resource: ResourceUri,
)
