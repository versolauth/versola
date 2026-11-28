package versola.central.configuration.roles

import versola.central.audit.AuditHook
import versola.central.configuration.clients.ClientId
import versola.central.configuration.permissions.Permission
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateRoleRequest, UpdateRoleRequest}
import versola.central.users.{UpdateUserRolesRequest, UserId, UserService}
import versola.util.http.Observability
import zio.http.{Request, Response, Status}
import zio.metrics.{Metric, MetricLabel}
import zio.{IO, UIO, ZIO}

import scala.util.control.NoStackTrace

/** Who may give a role, and what a role may hold: nobody grants what they do not have.
  *
  * Every route of the admin API is open to whoever edge lets through for its permission, and `access:manage` (roles) and
  * `users:manage` (a user's roles) are separate permissions. Left at that, an administrator who may assign roles can assign
  * the role that holds everything to themselves, and one who may edit roles can add any permission to the role they hold. So,
  * for an administrator (a user, as opposed to a service or a client):
  *
  *  - a user's roles are changed only for roles whose permissions the administrator holds themselves, in either direction
  *    (granting and taking away), and never for the administrator's own account;
  *  - a role is created only with permissions the administrator holds; changed or deleted only if it holds nothing the
  *    administrator does not, and given only permissions the administrator holds.
  *
  *  - the roles that every new registration is given (`defaultRoles` of the challenge settings) are held to the same ceiling: only
  *    the roles that are added are checked, so a tenant whose `defaultRoles` a higher administrator set can still have its other
  *    settings changed.
  *
  * What the administrator holds is the permissions of their active roles in the tenant of their token, read from the role cache,
  * as edge reads them for the administrator's own calls: a role that has just lost a permission is held at its old width until the
  * cache refreshes, which is the same delay edge applies. The role under change is read from the repository. A role no tenant has
  * yet defined grants nothing and is not refused.
  *
  * ==Who is the administrator==
  * The `actorId` and `actorTenantId` edge puts on every call from the verified token ([[AuditHook]]). A call without them, or
  * made by a client (a client credentials token's subject is the client), is a service's: not held to this, as every direct
  * caller of central has always been -- the shared secret it presents is the whole of its authority. Refusing such calls would
  * stop the load emulator, the operator's scripts and a deployment whose edge has not yet read the registration of an endpoint.
  * Such a call is counted (`central_role_change_unchecked_total{route,reason}`), so that an administrator's call that arrived without
  * the parameters (an endpoint registered without its inject rules) can be seen. A subject that is neither a client nor a valid user
  * id is refused: it is not a service's call, and what it holds cannot be known.
  */
object RoleGrantGuard:

  /** What the checks read. */
  type Env = RoleService & UserService

  /** An administrator who is a user. `tenantId` is the tenant of their token; it is absent only when edge did not send it, which
    * is refused, since their permissions cannot then be known. */
  case class Actor(userId: UserId, tenantId: Option[TenantId])

  /** The reason, naming the roles and permissions, which are the administrator's own to see, and no secret. */
  final class Denied(val message: String) extends RuntimeException(message) with NoStackTrace

  private def param(request: Request, name: String): Option[String] =
    request.url.queryParams.getAll(name).headOption.map(_.trim).filter(_.nonEmpty)

  /** Who is behind the call. */
  private enum Subject:
    case Admin(actor: Actor)
    /** A service's or a client's call: not held to the ceiling. `reason` says which, for the count. */
    case Service(reason: String)
    /** An `actorId` that is neither a client nor a user id. */
    case Malformed

  private def subjectOf(request: Request): Subject =
    val sub = param(request, AuditHook.ActorIdParam)
    val client = param(request, AuditHook.ActorClientParam).map(ClientId(_))
    sub match
      case None => Subject.Service("no_actor")
      case Some(id) if client.contains(ClientId(id)) => Subject.Service("client")
      case Some(id) =>
        UserId.parse(id).fold(
          _ => Subject.Malformed,
          userId => Subject.Admin(Actor(userId, param(request, AuditHook.ActorTenantParam).map(TenantId(_)))),
        )

  private val unchecked = Metric.counter("central_role_change_unchecked_total")

  /** The routes' answer to a refused change: `403` with the reason, recorded as the request's error (the audit log records it as
    * denied). Used as `.catchSome(RoleGrantGuard.refused)` on the handler. */
  val refused: PartialFunction[Throwable, UIO[Response]] =
    case denied: Denied =>
      Observability.setError("role_grant_refused", Some(denied.message)).as(Response.text(denied.message).status(Status.Forbidden))

  /** Runs `check` for the administrator behind the request. A call that is not an administrator's is not checked, and is counted under
    * `route`; one whose subject is not a known kind is refused. */
  private def forActor(request: Request, route: String)(check: Actor => ZIO[Env, Throwable, Unit]): ZIO[Env, Throwable, Unit] =
    subjectOf(request) match
      case Subject.Admin(actor) => check(actor)
      case Subject.Service(reason) => unchecked.tagged(Set(MetricLabel("route", route), MetricLabel("reason", reason))).increment
      case Subject.Malformed =>
        ZIO.fail(Denied("the caller is neither a client nor a user, so what they hold cannot be checked"))

  /** What `needed` holds that `held` does not. */
  def missing(held: Set[Permission], needed: Set[Permission]): Set[Permission] = needed -- held

  private def describe(permissions: Set[Permission]): String = permissions.toList.sorted.mkString(", ")

  /** The permissions the administrator holds now. */
  private def held(actor: Actor): ZIO[Env, Throwable, Set[Permission]] =
    for
      tenantId <-
        ZIO.fromOption(actor.tenantId).orElseFail(Denied("the tenant of the caller's token is unknown, so what they hold cannot be checked"))
      roleIds <- ZIO.serviceWithZIO[UserService](_.getRoles(actor.userId, tenantId))
      permissions <- ZIO.serviceWithZIO[RoleService](_.getActivePermissionsForRoles(tenantId, roleIds.toSet))
    yield permissions

  /** `PATCH /users/roles`. */
  def checkAssignment(request: Request, body: UpdateUserRolesRequest): ZIO[Env, Throwable, Unit] = forActor(request, "users_roles"): actor =>
    val changed = body.add ++ body.remove
    for
      _ <- ZIO.fail(Denied("a user may not change their own roles")).when(body.userId == actor.userId && changed.nonEmpty)
      _ <- ZIO.unless(changed.isEmpty):
        for
          actorHeld <- held(actor)
          beyond <- beyondOf(actorHeld, body.tenantId, changed)
          _ <- ZIO.fail(
            Denied(
              "roles may be given or taken away only if they grant nothing the caller does not hold; " + describeBeyond(beyond),
            ),
          ).when(beyond.nonEmpty)
        yield ()
    yield ()

  /** `POST /configuration/roles`: a role is created with permissions the caller holds. */
  def checkCreate(request: Request, body: CreateRoleRequest): ZIO[Env, Throwable, Unit] = forActor(request, "roles_create"): actor =>
    held(actor).flatMap(actorHeld => refuseBeyond(actorHeld, body.permissions, "a role may be created only with permissions the caller holds"))

  /** `PUT /configuration/roles`: the permissions added are held by the caller, and so is everything the role holds now. */
  def checkUpdate(request: Request, body: UpdateRoleRequest): ZIO[Env, Throwable, Unit] = forActor(request, "roles_update"): actor =>
    for
      actorHeld <- held(actor)
      current <- currentPermissions(body.tenantId, body.id)
      _ <- refuseBeyond(actorHeld, current, "a role may be changed only if it holds nothing the caller does not hold")
      _ <- refuseBeyond(actorHeld, body.permissions.add, "a role may be given only permissions the caller holds")
    yield ()

  /** `DELETE /configuration/roles`. */
  def checkDelete(request: Request, tenantId: TenantId, id: RoleId): ZIO[Env, Throwable, Unit] = forActor(request, "roles_delete"): actor =>
    for
      actorHeld <- held(actor)
      current <- currentPermissions(tenantId, id)
      _ <- refuseBeyond(actorHeld, current, "a role may be deleted only if it holds nothing the caller does not hold")
    yield ()

  /** The challenge settings of a tenant: the roles that are added to `defaultRoles`, which every new registration is given, are
    * roles that grant nothing the caller does not hold. */
  def checkDefaultRoles(request: Request, tenantId: TenantId, added: Set[RoleId]): ZIO[Env, Throwable, Unit] =
    if added.isEmpty then ZIO.unit
    else
      forActor(request, "default_roles"): actor =>
        for
          actorHeld <- held(actor)
          beyond <- beyondOf(actorHeld, tenantId, added)
          _ <- ZIO.fail(
            Denied(
              "roles given to every new registration only if they grant nothing the caller does not hold; " + describeBeyond(beyond),
            ),
          ).when(beyond.nonEmpty)
        yield ()

  /** The roles among `ids` that hold permissions the administrator does not, read from the repository, with those permissions. A role
    * nobody has defined holds nothing. */
  private def beyondOf(actorHeld: Set[Permission], tenantId: TenantId, ids: Set[RoleId]): ZIO[Env, Throwable, List[(RoleId, Set[Permission])]] =
    ZIO.foreach(ids.toList)(id => ZIO.serviceWithZIO[RoleService](_.findRole(tenantId, id)).map(id -> _)).map: roles =>
      roles.flatMap((id, role) => role.map(r => id -> missing(actorHeld, r.permissions))).filter(_._2.nonEmpty)

  private def describeBeyond(beyond: List[(RoleId, Set[Permission])]): String =
    beyond.sortBy(_._1).map((id, perms) => s"role '$id' grants ${describe(perms)}").mkString("; ")

  /** What the role holds now, read from the repository; a role nobody has defined holds nothing. */
  private def currentPermissions(tenantId: TenantId, id: RoleId): ZIO[Env, Throwable, Set[Permission]] =
    ZIO.serviceWithZIO[RoleService](_.findRole(tenantId, id)).map(_.fold(Set.empty[Permission])(_.permissions))

  private def refuseBeyond(actorHeld: Set[Permission], needed: Set[Permission], rule: String): IO[Denied, Unit] =
    val beyond = missing(actorHeld, needed)
    ZIO.fail(Denied(s"$rule; not held: ${describe(beyond)}")).when(beyond.nonEmpty).unit
