package versola.user

import versola.util.{CoreConfig, JWT}
import versola.util.http.Unauthorized
import zio.ZIO
import zio.http.{Header, Request}
import zio.json.JsonCodec

def authorizeInternal(request: Request): ZIO[CoreConfig, Unauthorized.type, Unit] =
  request.header(Header.Authorization) match
    case Some(Header.Authorization.Bearer(token)) =>
      for
        config <- ZIO.service[CoreConfig]
        _ <- JWT.deserialize[InternalAuthClaims](token.stringValue, config.central.secretKey)
          .orElseFail(Unauthorized)
      yield ()

    case _ =>
      ZIO.fail(Unauthorized)

case class InternalAuthClaims() derives JsonCodec
