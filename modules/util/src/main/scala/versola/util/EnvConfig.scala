package versola.util

case class EnvConfig[+CustomConfig](
    runtime: EnvConfig.Runtime,
    telemetry: Option[EnvConfig.Telemetry],
    app: CustomConfig,
)

object EnvConfig:
  case class Telemetry(
      collector: String,
  )
  case class Runtime(
      env: EnvName,
  )
