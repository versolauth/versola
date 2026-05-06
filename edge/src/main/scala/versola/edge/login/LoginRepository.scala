package versola.edge.login

import versola.edge.model.State
import zio.{Duration, Task}

trait LoginRepository:

  def create(
      loginId: String,
      record: LoginRecord,
      ttl: Duration,
  ): Task[Unit]

  def find(loginId: String): Task[Option[LoginRecord]]

  def findByState(state: State): Task[Option[LoginRecord]]

  def delete(loginId: String): Task[Unit]

  def deleteByState(state: State): Task[Unit]
