package versola.edge.login

import versola.edge.model.State
import zio.{Duration, Task}

trait LoginRepository:

  def create(
      record: LoginRecord,
      ttl: Duration,
  ): Task[Unit]

  def findByState(state: State): Task[Option[LoginRecord]]

  def deleteByState(state: State): Task[Unit]
