lazy val root = project.in(file("."))
  .settings(
    commonSettings,
  )
  .aggregate(
    `postgres-implementation`,
    http,
    util,
    auth
  )

lazy val implementations = file("implementations")

lazy val modules = file("modules")

lazy val `postgres-implementation` = project.in(implementations / "postgres")
  .settings(
    name := "postgres-implementation",
    commonSettings,
    libraryDependencies ++= Dependencies.database.postgres,
  ).dependsOn(
    auth % CompileTest
  )

lazy val auth = project
  .in(file("auth"))
  .settings(
    name := "auth",
    commonSettings,
    libraryDependencies ++= Dependencies.core
  )
  .dependsOn(
    http % CompileTest,
    util % CompileTest,
  )


lazy val http = project.in(modules / "http")
  .settings(
    name := "http",
    commonSettings,
    libraryDependencies ++= Dependencies.http,
  ).dependsOn(
    util % CompileTest,
  )

lazy val util = project.in(modules / "util")
  .settings(
    name := "util",
    commonSettings,
    libraryDependencies ++= Dependencies.core,
  )

lazy val commonSettings =
  Seq(
    scalaVersion := "3.7.4",
    scalacOptions ++= Seq(
      "-deprecation",
      "-source:future",
      "-new-syntax",
      "-indent",
      "-preview",
      "-Wconf:msg=unused import:e",
      "-Wconf:msg=pattern selector should be an instance of Matchable:s",
    ),
    libraryDependencies ++= Dependencies.core,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )

val CompileTest = "compile->compile;test->test"

