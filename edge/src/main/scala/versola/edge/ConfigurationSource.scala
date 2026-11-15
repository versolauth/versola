package versola.edge

import versola.edge.partner.{PartnerMeCheck, PartnerMeSyncClient, PartnerOAuthClientsSyncClient}
import versola.util.SequentialLayers.*
import versola.util.cel.CelEvaluator
import versola.util.{ConfigSnapshot, SecurityService}
import zio.http.Client
import zio.{Scope, ZIO, ZLayer}

/** Boundary 1 of #555: where this edge's configuration comes from.
  *
  * The services that hold configuration -- `OAuthClientService`, `ResourceService`,
  * `PermissionService`, `DpopPolicyService`, `JwksService` -- read it through the sources
  * named here and through nothing else, so a mode plugs in by providing these same types and
  * the services stay as they are. [[EdgeMode.FirstParty]] provides central's sync clients
  * (`/edge/sync`). [[EdgeMode.Partner]] (#564) provides the same types from the partner surface of
  * the same central (`/edge/partner/sync`, over the same mutual TLS with the same edge-signed
  * token), which answers with the same shapes for the rows of the edge's partner only: no roles, no
  * permissions, and no credential for any client (the partner edge authenticates them itself,
  * see [[versola.edge.partner.PartnerOAuthClientsSyncClient]]).
  *
  * The token they authenticate to central with (`CentralSyncTokenService`) is returned beside the
  * sources because a partner edge makes its console's management calls with it; the certificate
  * enrolment of a client (`ClientCertificateEnrollment`) is the first-party implementation's own.
  */
type ConfigurationSource =
  AuthorizationPresetsSyncClient & OAuthClientsSyncClient & ClientPermissionsSyncClient & ResourcesSyncClient &
    RolesSyncClient & PermissionsSyncClient & DpopAlgorithmsSyncClient & DpopPolicySyncClient & JwksSyncClient &
    OverloadPolicySyncClient & AccessLogPolicySyncClient & RateLimitPolicySyncClient

object ConfigurationSource:
  type Requirements = Scope & Client & EdgeConfig & SecurityService & CelEvaluator & ConfigSnapshot

  type Provided = ConfigurationSource & EdgeCertificateHolder & CentralSyncTokenService

  /** The source `EdgeConfig.mode` names. */
  val live: ZLayer[Requirements, Throwable, Provided] =
    ZLayer.service[EdgeConfig].flatMap(env => forMode(env.get.mode))

  def forMode(mode: EdgeMode): ZLayer[Requirements, Throwable, Provided] =
    mode match
      case EdgeMode.FirstParty => central
      case EdgeMode.Partner => partner

  /** What a partner edge syncs, from `/edge/partner/sync` (#962 §4). `PartnerMeCheck` asks central who this edge is before
    * anything else is read, so a kind that disagrees with `mode`, or a suspended partner, stops the edge with the reason. */
  val partner: ZLayer[Requirements, Throwable, Provided] =
    EdgeCertificateHolder.live >++>
      CentralSyncTokenService.live >++>
      EdgeIdentityEnrollment.live >++>
      PartnerMeSyncClient.live >++>
      PartnerMeCheck.live >++>
      AuthorizationPresetsSyncClient.live >++>
      PartnerOAuthClientsSyncClient.live >++>
      ClientPermissionsSyncClient.live >++>
      ResourcesSyncClient.live >++>
      ZLayer.succeed[RolesSyncClient](new RolesSyncClient { override def getAll = ZIO.succeed(Map.empty) }) >++>
      ZLayer.succeed[PermissionsSyncClient](new PermissionsSyncClient { override def getAll = ZIO.succeed(Map.empty) }) >++>
      DpopAlgorithmsSyncClient.live >++>
      DpopPolicySyncClient.live >++>
      OverloadPolicySyncClient.live >++>
      AccessLogPolicySyncClient.live >++>
      RateLimitPolicySyncClient.live >++>
      JwksSyncClient.live

  /** Everything synced from central, in the order the app wired it before the boundary
    * existed: each client authenticates with the sync token, and the clients sync enrols
    * the certificates it is registered to generate.
    */
  val central: ZLayer[Requirements, Throwable, Provided] =
    EdgeCertificateHolder.live >++>
      CentralSyncTokenService.live >++>
      EdgeIdentityEnrollment.live >++>
      AuthorizationPresetsSyncClient.live >++>
      ClientCertificateEnrollment.live >++>
      OAuthClientsSyncClient.live >++>
      ClientPermissionsSyncClient.live >++>
      ResourcesSyncClient.live >++>
      RolesSyncClient.live >++>
      PermissionsSyncClient.live >++>
      DpopAlgorithmsSyncClient.live >++>
      DpopPolicySyncClient.live >++>
      OverloadPolicySyncClient.live >++>
      AccessLogPolicySyncClient.live >++>
      RateLimitPolicySyncClient.live >++>
      JwksSyncClient.live
