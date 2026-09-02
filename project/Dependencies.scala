import sbt.*

object Versions {
  val tethys = "0.29.7"
  val zio = "2.1.22"
  val zioConfig = "4.0.6"
  val zioMetrics = "2.5.4"
  val zioSchema = "1.7.5"
  val zioJson = "0.7.45"
  val zioOpenTelemetry = "3.1.12"
  val openTelemetry = "1.56.0"
  val openTelemetrySemConv = "1.37.0"
  val openTelemetrySemConvIncubating = "1.37.0-alpha"
  val zioHttp = "3.6.0"
  val zioLogging = "2.5.1"
  val flyway = "11.17.2"
  val magnum = "2.0.0-M2"
  val postgresql = "42.7.8"
  val hikari = "7.0.2"
  val libphonenumber = "9.0.19"
  val uuidGenerator = "5.1.1"
  val webauthn = "2.7.0"
  val bouncyCastle = "1.83"
  val scalajsDom = "2.8.0"
  val scalajsJavaTime = "2.6.0"
  val laminar = "17.2.0"
  val javamail = "2.0.1"
  val cel = "0.12.0"
  val jsonSchemaValidator = "2.0.4"
  val typesafeConfig = "1.4.3"
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
    "org.scalamock" %% "scalamock-zio" % "7.5.2" % Test,
    "commons-codec" % "commons-codec" % "1.20.0",
    "dev.zio" %% "zio-json" % Versions.zioJson,
    "org.bouncycastle" % "bcprov-jdk18on" % Versions.bouncyCastle,
    "jakarta.mail" % "jakarta.mail-api" % Versions.javamail,
    "org.eclipse.angus" % "angus-mail" % Versions.javamail,
    "com.nimbusds" % "nimbus-jose-jwt" % "10.6",
    "com.yubico" % "webauthn-server-core" % Versions.webauthn,
    "com.yubico" % "webauthn-server-attestation" % Versions.webauthn,
  )

  val http = Seq(
    "dev.zio" %% "zio-http" % Versions.zioHttp,
    "dev.zio" %% "zio-http-testkit" % Versions.zioHttp % Test,
    "dev.zio" %% "zio-http-datastar-sdk" % Versions.zioHttp,
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
  )

  val cel = Seq(
    "dev.cel" % "cel" % Versions.cel,
  )

  // migrate-tool's own dependency set -- deliberately NOT `database.postgres` above (that
  // includes magnumpg, a SQL query library migrate-tool has no use for -- it never runs a query,
  // only Flyway.migrate()) and deliberately NOT anything from `core`/`http` (ZIO, HikariCP, and
  // everything those pull in transitively, including CEL's own okhttp/okio -- see the long
  // comment on why migrate-tool used to depend on `util`/`util-postgres` for this instead, and
  // why that broke jlink). This list is meant to be read as the complete runtime classpath: if a
  // future change needs something not listed here, it should be added explicitly, not pulled in
  // by reaching for `core`/`http` again.
  val migrateTool = Seq(
    "org.postgresql" % "postgresql" % Versions.postgresql,
    "org.flywaydb" % "flyway-database-postgresql" % Versions.flyway,
    "org.flywaydb" % "flyway-core" % Versions.flyway,
    // Same library `util`'s VersolaApp and edge-postgres-impl's PostgresEdgeApp already use to
    // parse HOCON -- but declared directly here rather than picked up transitively through
    // `zio-config-typesafe` (part of the `http` Seq), which drags in zio-config and zio itself.
    // Plain `com.typesafe:config` has no dependencies of its own, so this doesn't reintroduce the
    // jlink problem the rest of this list was written to avoid.
    "com.typesafe" % "config" % Versions.typesafeConfig,
  )

  // 2.x is the Jackson-2 line; 3.x requires Jackson 3 and would break the
  // Jackson 2.22.0 pin in build.sbt's dependencyOverrides.
  val jsonSchema = Seq(
    "com.networknt" % "json-schema-validator" % Versions.jsonSchemaValidator,
  )
}
