package versola.user

import versola.user.model.*
import versola.util.{Email, Patch, Phone}
import zio.Task
import zio.json.ast.Json

import java.util.UUID

trait UserRepository:
  def findOrCreate(userId: UserId, credential: Either[Email, Phone]): Task[(UserRecord, WasCreated)]

  def create(id: UserId): Task[UserRecord]

  def find(id: UserId): Task[Option[UserRecord]]

  def findByCredential(credential: Either[Email, Phone]): Task[Option[UserRecord]]

  def upsert(
      id: UserId,
      version: UUID,
      email: Option[Email],
      phone: Option[Phone],
      login: Option[Login],
  ): Task[Unit]

  def patchClaims(id: UserId, patch: Json.Obj): Task[Unit]


