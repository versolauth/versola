package versola.edge

import versola.util.SequentialLayers.*
import versola.util.cel.CelEvaluator
import versola.util.{ConfigSnapshot, SecurityService}
import zio.http.Client
import zio.{Scope, ZLayer}

/** Boundary 1 of #555: where this edge's configuration comes from.
  *
  * The services that hold configuration -- `OAuthClientService`, `ResourceService`,
  * `PermissionService`, `DpopPolicyService`, `JwksService` -- read it through the sources
  * named here and through nothing else, so a mode plugs in by providing these same types and
  * the services stay as they are. [[EdgeMode.FirstParty]] provides central's sync clients.
  * The token they authenticate to central with (`CentralSyncTokenService`) and the
  * certificate enrolment one of them runs (`ClientCertificateEnrollment`) are that
  * implementation's own and stay inside it: nothing outside this boundary needs either, which
  * is what lets [[EdgeMode.Partner]] (#564) provide the same types from a local file and the
  * operator issuer's public discovery and JWKS without a token or an enrolment of its own.
  */
type ConfigurationSource =
  AuthorizationPresetsSyncClient & OAuthClientsSyncClient & ClientPermissionsSyncClient & ResourcesSyncClient &
    RolesSyncClient & PermissionsSyncClient & DpopAlgorithmsSyncClient & DpopPolicySyncClient & JwksSyncClient &
    OverloadPolicySyncClient & AccessLogPolicySyncClient & RateLimitPolicySyncClient

object ConfigurationSource:
  type Requirements = Scope & Client & EdgeConfig & SecurityService & CelEvaluator & ConfigSnapshot

  /** The source `EdgeConfig.mode` names. */
  val live: ZLayer[Requirements, Throwable, ConfigurationSource & EdgeCertificateHolder] =
    ZLayer.service[EdgeConfig].flatMap(env => forMode(env.get.mode))

  def forMode(mode: EdgeMode): ZLayer[Requirements, Throwable, ConfigurationSource & EdgeCertificateHolder] =
    mode match
      case EdgeMode.FirstParty => central
      // Unreachable through `EdgeConfig.validated`, which refuses the mode first; kept so the
      // boundary is total and a wiring that skips validation fails naming the same cause.
      case EdgeMode.Partner => ZLayer.fail(EdgeMode.NotImplemented(mode))

  /** Everything synced from central, in the order the app wired it before the boundary
    * existed: each client authenticates with the sync token, and the clients sync enrols
    * the certificates it is registered to generate.
    */
  val central: ZLayer[Requirements, Throwable, ConfigurationSource & EdgeCertificateHolder] =
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
