lazy val root = project.in(file("."))
  .settings(
    commonSettings,
  )
  .aggregate(
    util,
    `util-postgres`,
    `auth-postgres-impl`,
    auth,
    `edge-postgres-impl`,
    edge,
    central,
    `central-postgres-impl`
  )

lazy val util = project
  .in(file("util"))
  .settings(
    name := "util",
    commonSettings,
    libraryDependencies ++= Dependencies.core,
    libraryDependencies ++= Dependencies.http,
    libraryDependencies ++= Dependencies.cel,
  )

lazy val utilImplementations = file("util/implementations")

lazy val `util-postgres` = project.in(utilImplementations / "postgres")
  .settings(
    name := "util-postgres",
    commonSettings,
    libraryDependencies ++= Dependencies.database.postgres,
  ).dependsOn(
    util % CompileTest
  )

lazy val implementations = file("auth/implementations")

lazy val `auth-postgres-impl` = project.in(implementations / "postgres")
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "auth-postgres-impl",
    commonSettings,
    libraryDependencies ++= Dependencies.database.postgres,
    Compile / mainClass := Some("versola.PostgresOAuthApp"),
    sbtForkSettings
  ).dependsOn(
    auth % CompileTest,
    `util-postgres` % CompileTest
  )

lazy val auth = project
  .in(file("auth"))
  .settings(
    name := "auth",
    commonSettings,
    libraryDependencies ++= Dependencies.core,
    libraryDependencies ++= Dependencies.http
  )
  .dependsOn(
    util % CompileTest
  )

lazy val edgeImplementations = file("edge/implementations")

lazy val `edge-postgres-impl` = project.in(edgeImplementations / "postgres")
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "edge-postgres-impl",
    commonSettings,
    libraryDependencies ++= Dependencies.database.postgres,
    Compile / mainClass := Some("versola.PostgresEdgeApp"),
    sbtForkSettings
  ).dependsOn(
    edge % CompileTest,
    `util-postgres` % CompileTest
  )

lazy val edge = project
  .in(file("edge"))
  .settings(
    name := "edge",
    commonSettings,
    libraryDependencies ++= Dependencies.core,
    libraryDependencies ++= Dependencies.http,
  )
  .dependsOn(
    util % CompileTest
  )


lazy val central = project
  .in(file("central"))
  .settings(
    name := "central",
    commonSettings,
    libraryDependencies ++= Dependencies.core,
    libraryDependencies ++= Dependencies.http
  )
  .dependsOn(
    util % CompileTest
  )

lazy val centralImplementations = file("central/implementations")

lazy val `central-postgres-impl` = project.in(centralImplementations / "postgres")
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "central-postgres-impl",
    commonSettings,
    libraryDependencies ++= Dependencies.database.postgres,
    Compile / mainClass := Some("versola.PostgresCentralApp"),
    sbtForkSettings,
  ).dependsOn(
    central % CompileTest,
    `util-postgres` % CompileTest
  )

lazy val sbtForkSettings = Seq(
  fork := true,
  run / baseDirectory := (ThisBuild / baseDirectory).value,
  run / envVars := sys.env,
  run / javaOptions ++= sys.props
    .collect { case (key, value) if key.startsWith("env.") => s"-D$key=$value"}
    .toSeq,
)

lazy val commonSettings =
  Seq(
    scalaVersion := "3.8.1",
    // Keep all Jackson modules on one consistent version. Transitive deps drag the
    // datatype/dataformat modules (jsr310, jdk8, cbor) to 2.22.0, so core/databind/
    // annotations must match — otherwise cross-module NoSuchMethod/NoSuchField errors
    // occur at runtime (e.g. StreamReadConstraints.validateDocumentLength added in 2.16,
    // CLEAR_CURRENT_TOKEN_ON_CLOSE added in 2.20).
    dependencyOverrides ++= Seq(
      "com.fasterxml.jackson.core"       % "jackson-core"             % "2.22.0",
      "com.fasterxml.jackson.core"       % "jackson-databind"         % "2.22.0",
      "com.fasterxml.jackson.core"       % "jackson-annotations"      % "2.22",
      "com.fasterxml.jackson.datatype"   % "jackson-datatype-jsr310"  % "2.22.0",
      "com.fasterxml.jackson.datatype"   % "jackson-datatype-jdk8"    % "2.22.0",
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor"  % "2.22.0",
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-source:future",
      "-new-syntax",
      "-indent",
      "-Wconf:msg=unused import:e",
      "-Wconf:msg=pattern selector should be an instance of Matchable:s",
    ),
    libraryDependencies ++= Dependencies.core,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )

val CompileTest = "compile->compile;test->test"

