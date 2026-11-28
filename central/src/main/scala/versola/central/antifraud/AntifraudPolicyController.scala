package versola.central.antifraud

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.topology.CellRegistry
import versola.central.{CentralConfig, authorizeAuth}
import zio.*
import zio.http.{Method, Request, Response, Status, handler}
import zio.json.EncoderOps

/** What a cell copies the antifraud policy from: internal, called by every `auth` instance in the background with its
  * sync token, never on a request of a user. Only an `auth` asks: an edge's token is refused here (`authorizeAuth`).
  *
  * An instance asks for the state every few seconds (two versions and the settings, which are one small row), and for the
  * lists only when their version is not the one of its copy, with `since` that version: the answer is then the entries
  * changed after it and the ids removed, or the whole list when the changes do not reach back that far. A cell that cannot reach central goes on with the copy in
  * its own database, so nothing here is on the path of a sign-in.
  */
object AntifraudPolicyController extends AuditedController:
  type Env = Tracing & CentralConfig & EdgeService & CellRegistry & AntifraudSettingsService & AntifraudListService

  def endpoints = List(stateEndpoint, listsEndpoint)

  val stateEndpoint =
    (Method.GET / "antifraud" / "policy" / "sync").audited(Audit.Internal) -> handler { (request: Request) =>
      for
        _ <- authorizeAuth(request)
        state <- ZIO.serviceWithZIO[AntifraudSettingsService](_.state)
      yield Response.json(state.toJson)
    }

  val listsEndpoint =
    (Method.GET / "antifraud" / "policy" / "lists" / "sync").audited(Audit.Internal) -> handler { (request: Request) =>
      for
        _ <- authorizeAuth(request)
        since = request.url.queryParams.getAll("since").headOption
        response <- since.map(_.toLongOption) match
          case Some(None) => ZIO.succeed(Response.text("since is the version of the copy, a whole number").status(Status.BadRequest))
          case parsed => ZIO.serviceWithZIO[AntifraudListService](_.lists(parsed.flatten)).map(lists => Response.json(lists.toJson))
      yield response
    }
