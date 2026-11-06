package versola.edge

import zio.http.Routes

/** Boundary 3 of #555: the routes of this edge that face the inside of the installation
  * rather than its users -- `POST /service/configuration/sync` (`ServiceController`) today,
  * the revocation pull of #560 and any later cell-facing endpoint.
  *
  * Served only by a [[EdgeMode.FirstParty]] edge. A partner edge (#564) runs outside the
  * installation, where nothing is entitled to call these, so for it the surface is not
  * refused on each request but absent: the app mounts [[routes]] for its mode, and a request
  * to one of these paths on a partner edge matches nothing.
  */
object InternalSurface:
  type Env = ServiceController.Env

  def routes(mode: EdgeMode): Routes[Env, Throwable] =
    mode match
      case EdgeMode.FirstParty => ServiceController.routes
      case EdgeMode.Partner => Routes.empty
