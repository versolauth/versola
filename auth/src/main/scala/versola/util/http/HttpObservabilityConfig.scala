package versola.util.http

import zio.http.Path

case class HttpObservabilityConfig(
    masking: Map[Path, HttpObservabilityConfig.Masking] = Map.empty,
)

object HttpObservabilityConfig:
  val default: HttpObservabilityConfig = HttpObservabilityConfig()

  case class Masking(
      logRequestBody: Boolean = true,
      logResponseBody: Boolean = true,
  )
