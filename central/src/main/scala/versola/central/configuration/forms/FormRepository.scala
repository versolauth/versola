package versola.central.configuration.forms

import versola.util.CacheSource
import zio.Task

trait FormRepository extends CacheSource[Vector[FormRecord]]:
  def getAll: Task[Vector[FormRecord]]

  def find(id: FormId, version: Int): Task[Option[FormRecord]]

  def upsertForm(
      id: FormId,
      style: String,
      jsSource: Option[String],
      jsCompiled: Option[String],
      localizations: Map[String, Map[String, String]],
      properties: Vector[BackendProperty],
      activate: Boolean,
  ): Task[Unit]

  def setActiveVersion(id: FormId, version: Int): Task[Unit]

  def getLocales: Task[Vector[FormLocale]]

  def updateLocales(add: Vector[FormLocale], delete: Vector[String]): Task[Unit]
