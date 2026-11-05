package versola.central.audit

import versola.central.audit.AuditRoutes.Entry
import versola.central.configuration.tenants.TenantId
import versola.central.users.UserId
import versola.util.Patch
import versola.util.audit.{AuditContext, AuditKind}
import zio.schema.{Schema, StandardType}
import zio.{FiberRef, UIO, Unsafe, ZIO}

import java.util.UUID
import scala.util.NotGiven

/** What a controller says about a route to the audit log, with [[AuditedRoute]]: `(Method.POST / "x").audited(Audit.change("client", Audit.create, ...))`. */
object Audit:
  export AuditRoutes.{action, change, create, delete, update, user}

  /** A route that stores nothing and shows nothing worth a row: a list, a preview, a rendering. */
  val Unrecorded: Entry = Entry.Unrecorded

  /** Not an administrator's call: auth's and edge's syncs and callbacks, the operator's service surface. */
  val Internal: Entry = Entry.Internal

  val UserViewed: AuditKind = CentralAudit.AdminUserViewed
  val AuditQueried: AuditKind = CentralAudit.AdminAuditQueried

  /** A read that is an event of its own, recorded when it succeeds. */
  def read(kind: AuditKind, targetType: String): Entry = Entry.Read(kind, targetType)

  /** What the handler tells the log once it knows it, from the typed values it has: what the call acts on, and the tenant that is. */
  def target[A](id: A)(using target: AuditTarget[A]): UIO[Unit] =
    AuditContext.setTarget(PendingType, target.id(id)) *> ZIO.foreachDiscard(target.user(id))(AuditContext.setUser)

  /** For a target the API names with a plain `String` (a theme id, a locale code, a key id, an OTP template id): there is no newtype to
    * pass, so the text is given as it is. Everything that has a newtype goes through [[target]]. */
  def targetByText(id: String): UIO[Unit] = AuditContext.setTarget(PendingType, id)

  /** The tenant the target belongs to, when it is not the actor's. */
  def tenant(tenantId: TenantId): UIO[Unit] = tenantOfTarget.set(Some(tenantId.toString))

  def tenant(tenantId: Option[TenantId]): UIO[Unit] = ZIO.foreachDiscard(tenantId)(tenant)

  /** The type of a target is the route's declaration, which the hook puts in place of this marker. */
  private[audit] val PendingType = ""

  private[audit] val tenantOfTarget: FiberRef[Option[String]] = Unsafe.unsafe(FiberRef.unsafe.make(Option.empty[String])(using _))

  /** The names of the fields of an update that it sets, from the request as decoded: a field that is not `None`, and not a patch that adds
    * and removes nothing. `except` are the fields that name what is updated (the id), by name; a name the record does not have is a
    * defect, so a renamed field fails loudly in the route's spec instead of leaving the log without it. Never the values. */
  def changed[A](request: A, except: String*)(using schema: Schema[A]): UIO[Unit] =
    ZIO.suspendSucceed(AuditContext.setChanged(fieldNames(request, except, onlySet = true)))

  /** The names of the fields of a call that replaces a whole record: every field but the ones that name it, a `None` being a field set
    * to nothing. */
  def replaced[A](request: A, except: String*)(using schema: Schema[A]): UIO[Unit] =
    ZIO.suspendSucceed(AuditContext.setChanged(fieldNames(request, except, onlySet = false)))

  /** For a request that has no `Schema` (one that holds a password and must not have an encoder): the names of its fields that are set, each
    * with whether it is. */
  def changedFields(fields: (String, Boolean)*): UIO[Unit] = AuditContext.setChanged(fields.collect { case (name, true) => name }.toSet)

  private def fieldNames[A](request: A, except: Seq[String], onlySet: Boolean)(using schema: Schema[A]): Set[String] =
    recordOf(schema, request) match
      case Some((record, value)) =>
        val names = record.fields.map(_.fieldName.toString).toSet
        val unknown = except.filterNot(names.contains)
        if unknown.nonEmpty then throw IllegalStateException(s"Audit.changed: ${unknown.mkString(", ")} is not a field of the request")
        record.fields.filterNot(field => except.contains(field.fieldName.toString)).collect {
          case field if !onlySet || isSet(field.asInstanceOf[Schema.Field[Any, Any]].get(value)) => field.fieldName.toString
        }.toSet
      case None => throw IllegalStateException("Audit.changed: the request is not a record")

  /** The record a schema describes and the value as that record's own type: a record with many fields is a `Transform` of a generic one. */
  private def recordOf(schema: Schema[?], value: Any): Option[(Schema.Record[Any], Any)] =
    schema match
      case record: Schema.Record[?] => Some((record.asInstanceOf[Schema.Record[Any]], value))
      case transform: Schema.Transform[?, ?, ?] =>
        transform.asInstanceOf[Schema.Transform[Any, Any, Any]].g(value).toOption.flatMap(inner => recordOf(transform.schema, inner))
      case lazySchema: Schema.Lazy[?] => recordOf(lazySchema.schema, value)
      case _ => None

  private def isSet(value: Any): Boolean = value match
    case None => false
    case product: Product if product.productPrefix.startsWith("Patch") =>
      product.productIterator.exists:
        case collection: Iterable[?] => collection.nonEmpty
        case None => false
        case _ => true
    case _ => true

/** A typed value that identifies what a call acts on, as the log writes it: the id of a client, a tenant, a user. A bare `String` is not
  * one; the newtypes are. */
trait AuditTarget[A]:
  def id(value: A): String
  def user(value: A): Option[UUID] = None

object AuditTarget:
  /** A user, which is also the user the row is about. */
  given userId: AuditTarget[UserId] = new AuditTarget[UserId]:
    def id(value: UserId): String = value.toString
    override def user(value: UserId): Option[UUID] = Some(value)

  /** The string newtypes (`ClientId`, `TenantId`, `Permission`, ...): subtypes of `String` that are not `String` itself. */
  given stringId: [A <: String] => NotGiven[String <:< A] => AuditTarget[A] = new AuditTarget[A]:
    def id(value: A): String = value

  /** The UUID newtypes (`UserId`, ...). */
  given uuidId: [A <: UUID] => NotGiven[UUID <:< A] => AuditTarget[A] = new AuditTarget[A]:
    def id(value: A): String = value.toString
