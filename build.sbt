lazy val root = project.in(file("."))
  .settings(
    commonSettings,
  )
  .aggregate(
    `postgres-impl`,
    auth
  )

lazy val implementations = file("implementations")

lazy val `postgres-impl` = project.in(implementations / "postgres")
  .settings(
    name := "postgres-impl",
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
    libraryDependencies ++= Dependencies.core,
    libraryDependencies ++= Dependencies.http
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

