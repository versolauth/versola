lazy val root = project.in(file("."))
  .settings(
    commonSettings,
  )
  .aggregate(
    `oauth-postgres-impl`,
    auth,
    `edge-postgres-impl`,
    edge
  )

lazy val implementations = file("auth/implementations")

lazy val `oauth-postgres-impl` = project.in(implementations / "postgres")
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "oauth-postgres-impl",
    commonSettings,
    libraryDependencies ++= Dependencies.database.postgres,
    Compile / mainClass := Some("versola.PostgresOAuthApp"),
  ).dependsOn(
    auth % CompileTest
  )

lazy val auth = project
  .in(file("auth"))
  .settings(
    name := "auth",
    commonSettings,
    libraryDependencies ++= Dependencies.core,
    libraryDependencies ++= Dependencies.http
  )

lazy val edgeImplementations = file("edge/implementations")

lazy val `edge-postgres-impl` = project.in(edgeImplementations / "postgres")
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "edge-postgres-impl",
    commonSettings,
    libraryDependencies ++= Dependencies.database.postgres,
    Compile / mainClass := Some("versola.PostgresEdgeApp"),
  ).dependsOn(
    edge % CompileTest
  )

lazy val edge = project
  .in(file("edge"))
  .settings(
    name := "edge",
    commonSettings,
    libraryDependencies ++= Dependencies.core,
    libraryDependencies ++= Dependencies.http
  )
  .dependsOn(
    auth % CompileTest
  )

lazy val commonSettings =
  Seq(
    scalaVersion := "3.8.1",
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

