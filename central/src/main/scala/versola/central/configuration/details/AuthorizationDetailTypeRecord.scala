package versola.central.configuration.details

import versola.central.configuration.tenants.TenantId
import zio.json.ast.Json
import zio.prelude.Equal

/** A registered RFC 9396 authorization detail type: the `type` value clients may use and
  * the JSON Schema (2020-12) each detail object of that type is validated against. */
case class AuthorizationDetailTypeRecord(
    tenantId: TenantId,
    `type`: AuthorizationDetailType,
    description: Map[String, String],
    schema: Json.Obj,
) derives CanEqual

object AuthorizationDetailTypeRecord:
  given Equal[AuthorizationDetailTypeRecord] = Equal.make(_ == _)
