package versola.central

import versola.central.configuration.clients.OAuthClientService
import versola.central.configuration.edges.{EdgeId, EdgeService}
import versola.util.{JWT, Secret}
import versola.util.http.Unauthorized
import zio.ZIO
import zio.http.{Header, Request}
import zio.json.{JsonCodec, jsonField}

/** Verifies that a request carries the central secret via HTTP Basic.
  *
  * The secret is loaded from the `central-admin` OAuth client record (current or
  * previous, for rotation support). This means it never needs to be stored in env.
  */
def authorizeBasic(request: Request): ZIO[OAuthClientService, Unauthorized.type, Unit] =
  request.header(Header.Authorization) match
    case Some(Header.Authorization.Basic(_, password)) =>
      for
        provided <- ZIO.fromEither(Secret.fromBase64Url(password.stringValue)).orElseFail(Unauthorized)
        service  <- ZIO.service[OAuthClientService]
        valid    <- service.verifySecret(provided).orElseFail(Unauthorized)
        _        <- ZIO.unless(valid)(ZIO.fail(Unauthorized))
      yield ()

    case _ =>
      ZIO.fail(Unauthorized)

def authorizeInternal(request: Request): ZIO[CentralConfig & EdgeService, Unauthorized.type, Option[EdgeId]] =
  request.header(Header.Authorization) match
    case Some(Header.Authorization.Bearer(token)) =>
      val raw = token.stringValue
      for
        header <- JWT.parseHeader[InternalAuthHeader](raw).orElseFail(Unauthorized)
        edgeId = header.edgeId.map(EdgeId(_))
        _ <- edgeId match
          case Some(id) =>
            for
              edgeService <- ZIO.service[EdgeService]
              edge <- edgeService.find(id).someOrFail(Unauthorized)
              _ <- JWT.deserialize[InternalAuthClaims](raw, edge.asPublicKeys, JWT.Type.JWT)
                .orElseFail(Unauthorized)
            yield ()
          case None =>
            for
              config <- ZIO.service[CentralConfig]
              _ <- JWT.deserialize[InternalAuthClaims](raw, config.secretKey, JWT.Type.JWT)
                .orElseFail(Unauthorized)
            yield ()
      yield edgeId

    case _ =>
      ZIO.fail(Unauthorized)

case class InternalAuthHeader(
    @jsonField("edge_id") edgeId: Option[String],
) derives JsonCodec

case class InternalAuthClaims() derives JsonCodec
