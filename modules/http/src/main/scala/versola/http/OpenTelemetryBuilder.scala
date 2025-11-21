package versola.http

import io.opentelemetry.api
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.{LogRecordProcessor, ReadWriteLogRecord, SdkLoggerProvider}
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.{BatchSpanProcessor, SpanExporter}
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.semconv.ServiceAttributes
import io.opentelemetry.semconv.incubating.ServiceIncubatingAttributes
import versola.util.{EnvConfig, EnvName}
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Exit, RIO, RLayer, Scope, TaskLayer, ZIO, ZLayer}

private[http] object OpenTelemetryBuilder:
  def live(
      serviceName: String,
  ): RLayer[ContextStorage & EnvConfig[Any], api.OpenTelemetry & Tracing] =
    ZLayer.fromZIO {
      ZIO.serviceWith[EnvConfig[Any]]: config =>
        openTelemetryProvider(
          env = config.runtime.env,
          serviceName = serviceName,
          config = config.telemetry,
        )
    }.flatten >+> OpenTelemetry.tracing(
      instrumentationScopeName = "versola.http",
      instrumentationVersion = None,
    ) ++ ZLayer.service[api.OpenTelemetry]

  private def openTelemetryProvider(
      env: EnvName,
      serviceName: String,
      config: Option[EnvConfig.Telemetry],
  ): TaskLayer[api.OpenTelemetry] = {
    val resource = config.fold(Resource.empty())(_ =>
      Resource.create(
        Attributes.of(
          ServiceAttributes.SERVICE_NAME,
          s"$serviceName-${env.value}",
        ),
      ),
    )
    OpenTelemetry.custom(
      for {
        tracerProvider <- buildTracerProvider(resource, config)
        meterProvider <- noopMeterProvider(resource)
        loggerProvider <- noopLogger(resource)
        openTelemetry <- ZIO.fromAutoCloseable(
          ZIO.succeed(
            OpenTelemetrySdk
              .builder()
              .setTracerProvider(tracerProvider)
              .setMeterProvider(meterProvider)
              .setLoggerProvider(loggerProvider)
              .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
              .build,
          ),
        )
      } yield openTelemetry,
    )
  }

  private def buildTracerProvider(
      resource: Resource,
      config: Option[EnvConfig.Telemetry],
  ): RIO[Scope, SdkTracerProvider] =
    for
      spanExporter <- config match
        case None =>
          Exit.succeed(NoopSpanExporter)
        case Some(config) =>
          ZIO.fromAutoCloseable:
            ZIO.succeed:
              OtlpGrpcSpanExporter.builder()
                .setEndpoint(config.collector)
                .build()

      spanProcessor <-
        ZIO.fromAutoCloseable:
          ZIO.succeed:
            BatchSpanProcessor.builder(spanExporter).build()

      tracerProvider =
        SdkTracerProvider
          .builder()
          .setResource(resource)
          .addSpanProcessor(spanProcessor)
          .build()

      meterProvider <- noopMeterProvider(resource)
    yield tracerProvider

  private def noopMeterProvider(resource: Resource): RIO[Scope, SdkMeterProvider] =
    ZIO.succeed:
      SdkMeterProvider
        .builder()
        .setResource(resource)
        .build()

  private def noopLogger(resource: Resource): RIO[Scope, SdkLoggerProvider] =
    ZIO.succeed:
      SdkLoggerProvider
        .builder()
        .setResource(resource)
        .addLogRecordProcessor(NoopLogRecordProcessor)
        .build()

  private object NoopSpanExporter extends SpanExporter:
    override def `export`(spans: java.util.Collection[SpanData]) =
      CompletableResultCode.ofSuccess()

    override def flush() =
      CompletableResultCode.ofSuccess()

    override def shutdown() =
      CompletableResultCode.ofSuccess()

  private object NoopLogRecordProcessor extends LogRecordProcessor:
    override def onEmit(context: Context, logRecord: ReadWriteLogRecord): Unit = ()
