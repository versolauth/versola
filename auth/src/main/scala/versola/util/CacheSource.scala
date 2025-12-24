package versola.util

import zio.*

trait CacheSource[A]:
  def getAll: Task[A]