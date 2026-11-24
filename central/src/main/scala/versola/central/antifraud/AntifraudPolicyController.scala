package versola.central.antifraud

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.topology.CellRegistry
import versola.central.{CentralConfig, authorizeInternal}
import zio.*
import zio.http.{Method, Request, Response, handler}
import zio.json.EncoderOps

/** What a cell copies the antifraud policy from: internal, called by every `auth` instance in the background with its
  * sync token, never on a request of a user.
  *
  * An instance asks for the state every few seconds (two versions and the settings, which are one small row), and for the
  * lists only when their version is not the one of its copy. A cell that cannot reach central goes on with the copy in
  * its own database, so nothing here is on the path of a sign-in.
  */
object AntifraudPolicyController extends AuditedController:
  type Env = Tracing & CentralConfig & EdgeService & CellRegistry & AntifraudSettingsService & AntifraudListService

  def endpoints = List(stateEndpoint, listsEndpoint)

  val stateEndpoint =
    (Method.GET / "antifraud" / "policy" / "sync").audited(Audit.Internal) -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        state <- ZIO.serviceWithZIO[AntifraudSettingsService](_.state)
      yield Response.json(state.toJson)
    }

  val listsEndpoint =
    (Method.GET / "antifraud" / "policy" / "lists" / "sync").audited(Audit.Internal) -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        lists <- ZIO.serviceWithZIO[AntifraudListService](_.active)
      yield Response.json(lists.toJson)
    }
