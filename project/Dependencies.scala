import sbt.*

object Versions {
  val tethys = "0.29.5"
  val zio = "2.1.19"
  val zioConfig = "4.0.4"
  val zioMetrics = "2.3.1"
  val zioSchema = "1.7.3"
  val zioJson = "0.7.44"
  val zioOpenTelemetry = "3.1.6"
  val openTelemetry = "1.51.0"
  val openTelemetrySemConv = "1.34.0"
  val openTelemetrySemConvIncubating = "1.34.0-alpha"
  val zioHttp = "3.3.3"
  val zioLogging = "2.5.0"
  val flyway = "11.10.0"
  val magnum = "2.0.0-M2"
  val postgresql = "42.7.7"
  val hikari = "6.3.0"
  val libphonenumber = "9.0.8"
  val uuidGenerator = "5.1.0"
  val webauthn = "2.7.0"
  val bouncyCastle = "1.81"
  val scalajsDom = "2.8.0"
  val scalajsJavaTime = "2.6.0"
  val laminar = "17.2.0"
  val javamail = "2.0.1"
}

object Dependencies {
  object database {
    val postgres = Seq(
      "org.postgresql" % "postgresql" % Versions.postgresql,
      "org.flywaydb" % "flyway-database-postgresql" % Versions.flyway,
      "com.augustnagro" %% "magnumpg" % Versions.magnum,
    )
  }

  val core = Seq(
    "dev.zio" %% "zio" % Versions.zio,
    "com.fasterxml.uuid" % "java-uuid-generator" % Versions.uuidGenerator,
    "com.googlecode.libphonenumber" % "libphonenumber" % Versions.libphonenumber,
    "com.augustnagro" %% "magnumzio" % Versions.magnum,
    "org.flywaydb" % "flyway-core" % Versions.flyway,
    "com.zaxxer" % "HikariCP" % Versions.hikari,
    "dev.zio" %% "zio-schema" % Versions.zioSchema,
    "dev.zio" %% "zio-schema-derivation" % Versions.zioSchema,
    "dev.zio" %% "zio-test" % Versions.zio % Test,
    "dev.zio" %% "zio-test-sbt" % Versions.zio % Test,
    "org.scalamock" %% "scalamock-zio" % "7.3.3" % Test,
    "commons-codec" % "commons-codec" % "1.18.0",
    "dev.zio" %% "zio-json" % Versions.zioJson,
    "org.bouncycastle" % "bcprov-jdk18on" % Versions.bouncyCastle,
    "jakarta.mail" % "jakarta.mail-api" % Versions.javamail,
    "org.eclipse.angus" % "angus-mail" % Versions.javamail,
  )


  val http = Seq(
    "dev.zio" %% "zio-http" % Versions.zioHttp,
    "dev.zio" %% "zio-http-testkit" % Versions.zioHttp % Test,
    "dev.zio" %% "zio-opentelemetry" % Versions.zioOpenTelemetry,
    "dev.zio" %% "zio-opentelemetry-zio-logging" % Versions.zioOpenTelemetry,
    "dev.zio" %% "zio-logging" % Versions.zioLogging,
    "dev.zio" %% "zio-logging-slf4j2-bridge" % Versions.zioLogging,
    "io.opentelemetry" % "opentelemetry-sdk" % Versions.openTelemetry,
    "io.opentelemetry" % "opentelemetry-sdk-trace" % Versions.openTelemetry,
    "io.opentelemetry" % "opentelemetry-exporter-logging-otlp" % Versions.openTelemetry,
    "io.opentelemetry" % "opentelemetry-exporter-otlp" % Versions.openTelemetry,
    "io.opentelemetry.semconv" % "opentelemetry-semconv" % Versions.openTelemetrySemConv,
    "io.opentelemetry.semconv" % "opentelemetry-semconv-incubating" % Versions.openTelemetrySemConvIncubating,
    "dev.zio" %% "zio-config" % Versions.zioConfig,
    "dev.zio" %% "zio-config-magnolia" % Versions.zioConfig,
    "dev.zio" %% "zio-config-typesafe" % Versions.zioConfig,
    "dev.zio" %% "zio-metrics-connectors" % Versions.zioMetrics,
    "dev.zio" %% "zio-metrics-connectors-prometheus" % Versions.zioMetrics,
    "dev.zio" %% "zio-schema-derivation" % Versions.zioSchema,
    "dev.zio" %% "zio-schema-json" % Versions.zioSchema,
    "com.nimbusds" % "nimbus-jose-jwt" % "10.3.1",
    "com.tethys-json" %% "tethys-core" % Versions.tethys,
    "com.tethys-json" %% "tethys-jackson213" % Versions.tethys
  )



}
