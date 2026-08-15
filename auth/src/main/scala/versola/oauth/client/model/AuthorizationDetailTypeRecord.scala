package versola.oauth.client.model

import zio.json.JsonCodec
import zio.json.ast.Json

/** A registered RFC 9396 authorization detail type and the JSON Schema (2020-12) that
  * detail objects of that type are validated against. */
case class AuthorizationDetailTypeRecord(
    tenantId: TenantId,
    `type`: AuthorizationDetailType,
    schema: Json.Obj,
) derives JsonCodec
