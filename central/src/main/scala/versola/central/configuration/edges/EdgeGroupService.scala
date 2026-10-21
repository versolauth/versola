package versola.central.configuration.edges

import zio.{Task, URLayer, ZLayer}

/** The groups edges belong to. Created by an operator and never changed, so there is no update. */
trait EdgeGroupService:
  def getAll: Task[Vector[EdgeGroupRecord]]

  def find(id: EdgeGroupId): Task[Option[EdgeGroupRecord]]

  /** @return `false` when a group with this id already exists; its kind is left as it was. */
  def create(record: EdgeGroupRecord): Task[Boolean]

object EdgeGroupService:
  val live: URLayer[EdgeGroupRepository, EdgeGroupService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(repository: EdgeGroupRepository) extends EdgeGroupService:
    override def getAll: Task[Vector[EdgeGroupRecord]] = repository.getAll

    override def find(id: EdgeGroupId): Task[Option[EdgeGroupRecord]] = repository.find(id)

    override def create(record: EdgeGroupRecord): Task[Boolean] = repository.create(record)
