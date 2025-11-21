package versola.http

import zio.UIO

trait MetricsService:
  def get: UIO[String]


