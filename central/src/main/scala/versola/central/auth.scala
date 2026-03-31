package versola.central

import versola.central.configuration.tenants.TenantId
import versola.util.JWT
import versola.util.http.Unauthorized
import zio.ZIO
import zio.http.{Handler, HandlerAspect, Header, Request, Response}
import zio.json.JsonCodec

def authorizeInternal(request: Request): ZIO[CentralConfig, Unauthorized.type, Option[Set[TenantId]]] =
  request.header(Header.Authorization) match {
    case Some(Header.Authorization.Bearer(token)) =>
      for
        config <- ZIO.service[CentralConfig]
        claims <- JWT.deserialize[InternalAuthClaims](token.stringValue, config.secretKey)
          .orElseFail(Unauthorized)
      yield claims.tenantIds

    case _ =>
      ZIO.fail(Unauthorized)
  }

case class InternalAuthClaims(
    tenantIds: Option[Set[TenantId]],
) derives JsonCodec
