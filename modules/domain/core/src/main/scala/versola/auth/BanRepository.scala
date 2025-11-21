package versola.auth

import versola.user.model.Email
import versola.util.CacheSource
import zio.Task

trait BanRepository extends CacheSource[Set[Email]]:
  def getAll: Task[Set[Email]]

  def isBanned(email: Email): Task[Boolean]

  def ban(email: Email): Task[Unit]

