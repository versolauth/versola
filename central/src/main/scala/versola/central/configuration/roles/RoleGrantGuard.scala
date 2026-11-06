package versola.central.configuration.roles

import versola.central.audit.AuditHook
import versola.central.configuration.PatchPermissions
import versola.central.configuration.clients.ClientId
import versola.central.configuration.permissions.Permission
import versola.central.configuration.tenants.TenantId
import versola.central.users.{UpdateUserRolesRequest, UserId, UserService}
import versola.util.http.Observability
import zio.http.{Request, Response, Status}
import zio.{IO, ZIO}

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
  * What the administrator holds is the permissions of their active roles in the tenant of their token, which is what edge
  * checks the next call against. A role no tenant has yet defined grants nothing and is not refused.
  *
  * ==Who is the administrator==
  * The `actorId` and `actorTenantId` edge puts on every call from the verified token ([[AuditHook]]). A call without them, or
  * made by a client (a client credentials token's subject is the client), is a service's: not held to this, as every direct
  * caller of central has always been -- the shared secret it presents is the whole of its authority. Refusing such calls would
  * stop the load emulator, the operator's scripts and a deployment whose edge has not yet read the registration of an endpoint.
  */
object RoleGrantGuard:

  /** An administrator who is a user. `tenantId` is the tenant of their token; it is absent only when edge did not send it, which
    * is refused, since their permissions cannot then be known. */
  case class Actor(userId: UserId, tenantId: Option[TenantId])

  /** The reason, naming the roles and permissions, which are the administrator's own to see, and no secret. */
  final class Denied(val message: String) extends RuntimeException(message) with NoStackTrace

  private def param(request: Request, name: String): Option[String] =
    request.url.queryParams.getAll(name).headOption.map(_.trim).filter(_.nonEmpty)

  /** The administrator behind the call, or `None` for a call that is a service's or a client's. */
  def actorOf(request: Request): Option[Actor] =
    val sub = param(request, AuditHook.ActorIdParam)
    val client = param(request, AuditHook.ActorClientParam).map(ClientId(_))
    val isClient = sub.exists(id => client.contains(ClientId(id)))
    sub.filterNot(_ => isClient)
      .flatMap(UserId.parse(_).toOption)
      .map(userId => Actor(userId, param(request, AuditHook.ActorTenantParam).map(TenantId(_))))

  /** Runs `check` for the administrator behind the request; a call that is not an administrator's is not checked. */
  def enforce(request: Request)(
      check: Actor => ZIO[RoleService & UserService, Denied | Throwable, Unit],
  ): ZIO[RoleService & UserService, Denied | Throwable, Unit] =
    actorOf(request).fold(ZIO.unit)(check)

  /** The answer to a refused change: `403` with the reason, recorded as the request's error. The audit log records it as denied. */
  def refusal(denied: Denied): ZIO[Any, Nothing, Response] =
    Observability.setError("role_grant_refused", Some(denied.message)).as(Response.text(denied.message).status(Status.Forbidden))

  /** What `needed` holds that `held` does not. */
  def missing(held: Set[Permission], needed: Set[Permission]): Set[Permission] = needed -- held

  private def describe(permissions: Set[Permission]): String = permissions.toList.sorted.mkString(", ")

  /** The permissions the administrator holds now. */
  private def held(actor: Actor): ZIO[RoleService & UserService, Denied | Throwable, Set[Permission]] =
    for
      tenantId <-
        ZIO.fromOption(actor.tenantId).orElseFail(Denied("the tenant of the caller's token is unknown, so what they hold cannot be checked"))
      roleIds <- ZIO.serviceWithZIO[UserService](_.getRoles(actor.userId, tenantId))
      permissions <- ZIO.serviceWithZIO[RoleService](_.getActivePermissionsForRoles(tenantId, roleIds.toSet))
    yield permissions

  /** `PATCH /users/roles`. */
  def checkAssignment(actor: Actor, request: UpdateUserRolesRequest): ZIO[RoleService & UserService, Denied | Throwable, Unit] =
    val changed = request.add ++ request.remove
    for
      _ <- ZIO.fail(Denied("a user may not change their own roles")).when(request.userId == actor.userId && changed.nonEmpty)
      _ <- ZIO.unless(changed.isEmpty):
        for
          actorHeld <- held(actor)
          roles <- ZIO.foreach(changed.toList)(id => ZIO.serviceWithZIO[RoleService](_.findRole(request.tenantId, id)).map(id -> _))
          beyond = roles.flatMap((id, role) => role.map(r => id -> missing(actorHeld, r.permissions))).filter(_._2.nonEmpty)
          _ <- ZIO.fail(
            Denied(
              "roles may be given or taken away only if they grant nothing the caller does not hold; " +
                beyond.sortBy(_._1).map((id, perms) => s"role '$id' grants ${describe(perms)}").mkString("; "),
            ),
          ).when(beyond.nonEmpty)
        yield ()
    yield ()

  /** `POST /configuration/roles`: a role is created with permissions the caller holds. */
  def checkCreate(actor: Actor, permissions: Set[Permission]): ZIO[RoleService & UserService, Denied | Throwable, Unit] =
    held(actor).flatMap(actorHeld => refuseBeyond(actorHeld, permissions, "a role may be created only with permissions the caller holds"))

  /** `PUT /configuration/roles`: the permissions added are held by the caller, and so is everything the role holds now. */
  def checkUpdate(
      actor: Actor,
      tenantId: TenantId,
      id: RoleId,
      patch: PatchPermissions,
  ): ZIO[RoleService & UserService, Denied | Throwable, Unit] =
    for
      actorHeld <- held(actor)
      current <- ZIO.serviceWithZIO[RoleService](_.findRole(tenantId, id)).map(_.fold(Set.empty[Permission])(_.permissions))
      _ <- refuseBeyond(actorHeld, current, "a role may be changed only if it holds nothing the caller does not hold")
      _ <- refuseBeyond(actorHeld, patch.add, "a role may be given only permissions the caller holds")
    yield ()

  /** `DELETE /configuration/roles`. */
  def checkDelete(actor: Actor, tenantId: TenantId, id: RoleId): ZIO[RoleService & UserService, Denied | Throwable, Unit] =
    for
      actorHeld <- held(actor)
      current <- ZIO.serviceWithZIO[RoleService](_.findRole(tenantId, id)).map(_.fold(Set.empty[Permission])(_.permissions))
      _ <- refuseBeyond(actorHeld, current, "a role may be deleted only if it holds nothing the caller does not hold")
    yield ()

  private def refuseBeyond(actorHeld: Set[Permission], needed: Set[Permission], rule: String): IO[Denied, Unit] =
    val beyond = missing(actorHeld, needed)
    ZIO.fail(Denied(s"$rule; not held: ${describe(beyond)}")).when(beyond.nonEmpty).unit
