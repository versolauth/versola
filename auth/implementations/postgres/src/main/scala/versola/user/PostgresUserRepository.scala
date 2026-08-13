package versola.user

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec
import com.augustnagro.magnum.pg.PgCodec.given
import versola.oauth.client.model.TenantId
import versola.role.model.RoleId
import versola.util.postgres.BasicCodecs
import versola.user.model.*
import versola.util.{Email, Phone}
import zio.{Task, ZIO, ZLayer}
import zio.json.*
import zio.json.ast.Json

import java.time.LocalDate
import java.util.UUID

class PostgresUserRepository(
    xa: TransactorZIO,
) extends UserRepository, BasicCodecs:

  override def register(
      userId: UserId,
      credential: Either[Email, Phone],
      tenantId: TenantId,
      roleIds: Set[RoleId],
  ): Task[UserRecord] =
    xa.transactMeasured("register-user"):
      val (user, _) = findOrCreateSql(userId, credential)
      if roleIds.nonEmpty then
        sql"""insert into user_roles (user_id, tenant_id, role_id)
              select ${user.id}, $tenantId, unnest($roleIds)
              on conflict do nothing""".update.run()
      user

  private def createByEmailQuery(id: UserId, email: Email) =
    sql"""insert into users (id, email, phone, login, claims, ui_locales)
          values ($id, $email, null, null, '{}'::jsonb, null)
          on conflict (email) where email is not null
          do update set email = excluded.email
          returning id, email, phone, login, claims, ui_locales, (xmax = 0) as created
       """.returning[(UserRecord, Boolean)]

  private def createByPhoneQuery(id: UserId, phone: Phone) =
    sql"""insert into users (id, email, phone, login, claims, ui_locales)
          values ($id, null, $phone, null, '{}'::jsonb, null)
          on conflict (phone) where phone is not null
            do update set phone = excluded.phone
          returning id, email, phone, login, claims, ui_locales, (xmax = 0) as created
       """.returning[(UserRecord, Boolean)]

  private def findOrCreateSql(userId: UserId, credential: Either[Email, Phone])(using DbCon) =
    credential match
      case Left(email) => createByEmailQuery(userId, email).run().head
      case Right(phone) => createByPhoneQuery(userId, phone).run().head

  override def find(id: UserId): Task[Option[UserRecord]] =
    xa.connectMeasured("find-user"):
      sql"select id, email, phone, login, claims, ui_locales from users where id = $id"
        .query[UserRecord]
        .run()
        .headOption

  override def findByLogin(login: Login): Task[Option[UserRecord]] =
    xa.connectMeasured("find-user-by-login"):
      sql"select id, email, phone, login, claims, ui_locales from users where login = $login"
        .query[UserRecord]
        .run()
        .headOption

  override def findByCredential(credential: Either[Email, Phone]): Task[Option[UserRecord]] =
    xa.connectMeasured("find-user-by-credential"):
      credential match
        case Left(email) => findByEmailQuery(email).run().headOption
        case Right(phone) => findByPhoneQuery(phone).run().headOption

  override def upsert(
      id: UserId,
      version: UUID,
      email: Option[Email],
      phone: Option[Phone],
      login: Option[Login],
  ): Task[Unit] =
    xa.connectMeasured("upsert-user"):
      sql"""insert into users (id, email, phone, login, claims, last_version)
            values ($id, $email, $phone, $login, '{}'::jsonb, $version)
            on conflict (id) do update set
              email = excluded.email,
              phone = excluded.phone,
              login = excluded.login,
              last_version = excluded.last_version
            where users.last_version is null or users.last_version < excluded.last_version
         """.update.run()
    .unit

  override def patchClaims(id: UserId, patch: Json.Obj): Task[Unit] =
    xa.connectMeasured("patch-user-claims"):
      sql"update users set claims = jsonb_strip_nulls(claims || $patch::jsonb) where id = $id".update.run()
    .unit

  override def delete(id: UserId): Task[Unit] =
    xa.connectMeasured("delete-user"):
      sql"delete from users where id = $id".update.run()
    .unit

  override def findRolesByUserAndTenant(userId: UserId, tenantId: TenantId): Task[List[RoleId]] =
    xa.connectMeasured("find-roles-by-user-and-tenant"):
      sql"SELECT role_id FROM user_roles WHERE user_id = $userId AND tenant_id = $tenantId"
        .query[String]
        .run()
        .map(RoleId(_))
        .toList

  override def findRolesByUser(userId: UserId): Task[Map[TenantId, List[RoleId]]] =
    xa.connectMeasured("find-roles-by-user"):
      sql"SELECT tenant_id, role_id FROM user_roles WHERE user_id = $userId"
        .query[(TenantId, RoleId)]
        .run()
        .groupMap(_._1)(_._2)
        .map((tenantId, roleIds) => tenantId -> roleIds.toList)

  override def updateRoles(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ): Task[Unit] =
    if add.isEmpty && remove.isEmpty then ZIO.unit
    else
      xa.transactMeasured("update-roles"):
        if remove.nonEmpty then
          sql"DELETE FROM user_roles WHERE user_id = $userId AND tenant_id = $tenantId AND role_id = ANY($remove)"
            .update.run()
        if add.nonEmpty then
          sql"""INSERT INTO user_roles (user_id, tenant_id, role_id)
                SELECT $userId, $tenantId, unnest($add)
                ON CONFLICT DO NOTHING"""
            .update.run()
      .unit

  private def findByPhoneQuery(phone: Phone) =
    sql"select id, email, phone, login, claims, ui_locales from users where phone = $phone".query[UserRecord]

  private def findByEmailQuery(email: Email) =
    sql"select id, email, phone, login, claims, ui_locales from users where email = $email".query[UserRecord]


  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[Email] = DbCodec.StringCodec.biMap(Email(_), identity[String])
  given DbCodec[Phone] = DbCodec.StringCodec.biMap(Phone(_), identity[String])
  given DbCodec[Login] = DbCodec.StringCodec.biMap(Login(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[RoleId] = DbCodec.StringCodec.biMap(RoleId(_), identity[String])
  given DbCodec[FirstName] = DbCodec.StringCodec.biMap(FirstName(_), identity[String])
  given DbCodec[MiddleName] = DbCodec.StringCodec.biMap(MiddleName(_), identity[String])
  given DbCodec[LastName] = DbCodec.StringCodec.biMap(LastName(_), identity[String])
  given DbCodec[BirthDate] = DbCodec.LocalDateCodec.biMap(BirthDate(_), identity[LocalDate])

  given DbCodec[Json.Obj] = jsonBCodec[Json.Obj]

  given DbCodec[List[String]] = PgCodec.SeqCodec[String].biMap(_.toList, _.toSeq)

  given DbCodec[UserRecord] = DbCodec.derived[UserRecord]

object PostgresUserRepository:
  def live: ZLayer[TransactorZIO, Throwable, UserRepository] =
    ZLayer.fromFunction(PostgresUserRepository(_))
