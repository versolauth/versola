package versola.central

import versola.central.configuration.edges.{EdgeId, EdgeService}
import versola.central.configuration.jwks.JwksService
import versola.util.JWT
import versola.util.http.Unauthorized
import zio.ZIO
import zio.http.{Header, Request}
import zio.json.{JsonCodec, jsonField}

/** Verifies an admin-console access token issued by `auth` (RS256, `at+jwt`)
  * against auth's JWKS and returns its admin claims.
  */
def authorizeAdmin(request: Request): ZIO[JwksService, Unauthorized.type, AdminClaims] =
  request.header(Header.Authorization) match
    case Some(Header.Authorization.Bearer(token)) =>
      for
        jwksService <- ZIO.service[JwksService]
        keys <- jwksService.getPublicKeys
        claims <- JWT.deserialize[AdminClaims](token.stringValue, keys, JWT.Type.AccessToken)
          .orElseFail(Unauthorized)
      yield claims

    case _ =>
      ZIO.fail(Unauthorized)

case class AdminClaims(
    @jsonField("sub") subject: String,
    @jsonField("client_id") clientId: Option[String],
    @jsonField("admin_roles") adminRoles: Option[Map[String, List[String]]],
) derives JsonCodec

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
              _ <- JWT.deserialize[InternalAuthClaims](raw, config.secretKey)
                .orElseFail(Unauthorized)
            yield ()
      yield edgeId

    case _ =>
      ZIO.fail(Unauthorized)

case class InternalAuthHeader(
    @jsonField("edge_id") edgeId: Option[String],
) derives JsonCodec

case class InternalAuthClaims() derives JsonCodec
