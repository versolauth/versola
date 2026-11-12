package versola.edge.partner

import versola.edge.{CentralSyncTokenService, EdgeConfig, EdgeMode, EdgeService}
import versola.util.http.Observability
import zio.http.{Client, Routes}
import zio.{Scope, Task, URLayer, ZIO, ZLayer}

import java.nio.file.Path

/** Boundary 4 of #564, beside the three of #555: the routes a partner edge serves for its own console, `/partner/api` and what
  * `/partner/` serves (the sign-in and sign-out paths and the console's files).
  *
  * Served only by a [[EdgeMode.Partner]] edge; on a first-party edge the surface is absent, not refused, so a request to one of its
  * paths matches nothing, as for [[versola.edge.InternalSurface]] on a partner edge. The app mounts [[routes]] where the mode is known.
  */
trait PartnerSurface:
  def routes: Routes[Any, Throwable]

object PartnerSurface:
  val absent: PartnerSurface = new PartnerSurface:
    override def routes: Routes[Any, Throwable] = Routes.empty

  type Requirements =
    Scope & Client & EdgeConfig & CentralSyncTokenService & StaffRepository & EdgeService

  val live: ZLayer[Requirements & versola.util.ConfigSnapshot, Throwable, PartnerSurface] =
    ZLayer.service[EdgeConfig].flatMap(config => forMode(config.get.mode))

  def forMode(mode: EdgeMode): ZLayer[Requirements & versola.util.ConfigSnapshot, Throwable, PartnerSurface] =
    mode match
      case EdgeMode.FirstParty => ZLayer.succeed[PartnerSurface](absent)
      case EdgeMode.Partner => partner

  /** What a partner edge serves: the team, the cache of `/edge/partner/me` and the link to central, and the routes over them.
    * The first administrators of `partner.admins` are made invites here, at every start. */
  val partner: ZLayer[Requirements & versola.util.ConfigSnapshot, Throwable, PartnerSurface] =
    (PartnerMeSyncClient.live >>> PartnerState.live) ++ PartnerCentral.live ++ Identifiers.live ++ ZLayer.environment[Requirements] >+>
      StaffService.live >>>
      ZLayer.fromZIO:
        for
          config <- ZIO.service[EdgeConfig]
          staff <- ZIO.service[StaffService]
          _ <- staff.applyAdmins(config.partner.admins)
          state <- ZIO.service[PartnerState]
          central <- ZIO.service[PartnerCentral]
          edgeService <- ZIO.service[EdgeService]
          api = PartnerApi(edgeService = edgeService, staff = staff, state = state, central = central, config = config)
          console = PartnerConsole(state, config.partner.consoleDirectory.map(Path.of(_).nn))
        yield new PartnerSurface:
          override def routes: Routes[Any, Throwable] = api.routes ++ console.routes
