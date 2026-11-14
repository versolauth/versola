package versola.central.audit

import versola.central.antifraud.*
import versola.central.configuration.challenges.OtpChallengeController
import versola.central.configuration.clients.est.ClientEstController
import versola.central.configuration.clients.{AuthorizationPresetController, ClientController}
import versola.central.configuration.delivery.DeliverySettingsController
import versola.central.configuration.details.AuthorizationDetailTypeController
import versola.central.configuration.edges.EdgeController
import versola.central.configuration.forms.FormController
import versola.central.configuration.jwks.JwksController
import versola.central.configuration.locales.LocaleController
import versola.central.configuration.metadata.ServerMetadataController
import versola.central.configuration.permissions.PermissionController
import versola.central.configuration.resources.ResourceController
import versola.central.configuration.roles.RoleController
import versola.central.configuration.scopes.ScopeController
import versola.central.configuration.system.SystemSettingsController
import versola.central.configuration.tenants.TenantController
import versola.central.configuration.themes.ThemeController
import versola.central.users.{ServiceController, UserController}
import versola.util.audit.{AuditEvents, AuditTenants}
import zio.http.*

/** For a spec of the hook that serves its own handlers on the paths of real routes: each route is given the declaration the controller
  * of that route carries, as the app does with the route itself. */
object TestAuditHook:
  private val declared: Map[(Method, String), AuditRoutes.Entry] =
    List(
      TenantController.endpoints,
      PermissionController.endpoints,
      ResourceController.endpoints,
      ClientController.endpoints,
      ClientEstController.endpoints,
      AuthorizationPresetController.endpoints,
      ScopeController.endpoints,
      AuthorizationDetailTypeController.endpoints,
      RoleController.endpoints,
      EdgeController.endpoints,
      FormController.endpoints,
      LocaleController.endpoints,
      ThemeController.endpoints,
      OtpChallengeController.endpoints,
      SystemSettingsController.endpoints,
      DeliverySettingsController.endpoints,
      UserController.endpoints,
      AntifraudListController.endpoints,
      AntifraudSettingsController.endpoints,
      AntifraudCountersController.endpoints,
      AntifraudStatusController.endpoints,
      JwksController.endpoints,
      ServerMetadataController.endpoints,
      ServiceController.endpoints,
    ).flatten.map(e => (e.route.routePattern.method, e.route.routePattern.pathCodec.render) -> e.entry).toMap

  val middleware: Middleware[AuditEvents & AuditTenants] = new Middleware[AuditEvents & AuditTenants]:
    def apply[Env1 <: AuditEvents & AuditTenants, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
      Routes.fromIterable(
        routes.routes.map: route =>
          val key = (route.routePattern.method, route.routePattern.pathCodec.render)
          declared.get(key).fold(route)(entry =>
            AuditHook.wrap(AuditedRoute[Env1](route.asInstanceOf[Route[Env1, Throwable]], entry)).asInstanceOf[Route[Env1, Err]],
          ),
      )
