lazy val root = project.in(file("."))
  .settings(
    commonSettings,
  )
  .aggregate(
    domain,
    `domain-postgres`,
    http,
    util,
    auth
  )

lazy val modules = file("modules")
lazy val postgres = modules / "postgres"

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
    domain % CompileTest,
    `domain-postgres` % CompileTest
  )

lazy val domain = project.in(modules / "domain" / "core")
  .settings(
    name := "domain",
    commonSettings,
  ).dependsOn(
    util % CompileTest,
  )

lazy val `domain-postgres` = project.in(modules / "domain" / "postgres")
  .settings(
    name := "domain-postgres",
    commonSettings,
    libraryDependencies ++= Dependencies.database.postgres,
  ).dependsOn(
    util % CompileTest,
    domain % CompileTest,
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

