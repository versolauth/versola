package versola.util.http

import zio.UIO

trait MetricsService:
  def get: UIO[String]


