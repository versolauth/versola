package versola.user

import versola.user.model.*
import versola.util.{Email, Patch, Phone}
import zio.Task
import zio.json.ast.Json

trait UserRepository:
  def findOrCreate(userId: UserId, credential: Either[Email, Phone]): Task[(UserRecord, WasCreated)]

  def create(id: UserId): Task[UserRecord]

  def find(id: UserId): Task[Option[UserRecord]]

  def findByCredential(credential: Either[Email, Phone]): Task[Option[UserRecord]]

  def upsert(id: UserId, email: Option[Email], phone: Option[Phone], login: Option[Login], claims: Json.Obj): Task[Unit]

  def patch(
      id: UserId,
      email: Option[Patch[Email]],
      phone: Option[Patch[Phone]],
      login: Option[Patch[Login]],
      claims: Option[Json.Obj],
  ): Task[Unit]


