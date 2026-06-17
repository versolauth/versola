package versola.central.configuration.locales

import zio.Task

trait LocaleRepository:
  def getAll: Task[Vector[LocaleRecord]]

  def update(add: Vector[LocaleRecord], delete: Vector[String]): Task[Unit]

  def setDefault(code: String): Task[Unit]
