package versola.util.http

import io.opentelemetry.api
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{ULayer, ZLayer}


object NoopTracing:
  val layer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )
