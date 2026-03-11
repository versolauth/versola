package versola.user

import versola.user.model.*
import versola.util.{Email, Phone}
import zio.Task

trait UserRepository:
  def findOrCreate(userId: UserId, credential: Either[Email, Phone]): Task[(UserRecord, WasCreated)]

  def create(id: UserId): Task[UserRecord]

  def find(id: UserId): Task[Option[UserRecord]]

  def findByCredential(credential: Either[Email, Phone]): Task[Option[UserRecord]]


