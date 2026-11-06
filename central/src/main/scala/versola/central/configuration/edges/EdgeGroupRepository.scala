package versola.central.configuration.edges

import zio.Task

trait EdgeGroupRepository:
  def getAll: Task[Vector[EdgeGroupRecord]]

  def find(id: EdgeGroupId): Task[Option[EdgeGroupRecord]]

  /** @return `false` when a group with this id already exists; its kind is not changed. */
  def create(record: EdgeGroupRecord): Task[Boolean]
