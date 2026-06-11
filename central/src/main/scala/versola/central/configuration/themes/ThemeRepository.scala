package versola.central.configuration.themes

import versola.util.CacheSource
import zio.Task

trait ThemeRepository extends CacheSource[Vector[ThemeRecord]]:
  def getAll: Task[Vector[ThemeRecord]]
  def create(theme: ThemeRecord): Task[Unit]
  def update(theme: ThemeRecord): Task[Unit]
  def delete(id: String): Task[Unit]
