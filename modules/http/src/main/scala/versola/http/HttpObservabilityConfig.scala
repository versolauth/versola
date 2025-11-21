package versola.http

import versola.http.HttpObservabilityConfig.Masking
import zio.http.Path

case class HttpObservabilityConfig(
    masking: Map[Path, Masking],
)

object HttpObservabilityConfig:
  val default = HttpObservabilityConfig(
    masking = Map.empty,
  )
  
  case class Masking(
      logRequestBody: Boolean = true,
      logResponseBody: Boolean = true,
  )
