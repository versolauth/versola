lazy val root = project.in(file("."))
  .settings(
    commonSettings,
    Test / compile := (Test / compile)
      .dependsOn(e2e / Test / compile).value
  )
  .aggregate(
    util,
    `util-postgres`,
    `auth-postgres-impl`,
    auth,
    `edge-postgres-impl`,
    edge,
    central,
    `central-postgres-impl`,
  )

lazy val util = project
  .in(file("util"))
  .settings(
    name := "util",
    commonSettings,
    libraryDependencies ++= Dependencies.core,
    libraryDependencies ++= Dependencies.http,
    libraryDependencies ++= Dependencies.cel,
    libraryDependencies ++= Dependencies.jsonSchema,
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
    libraryDependencies ++= Dependencies.cache,
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
    Test / parallelExecution := false,
    sbtForkSettings,
  ).dependsOn(
    central % CompileTest,
    `util-postgres` % CompileTest
  )

lazy val e2e = project
  .in(file("e2e"))
  .settings(
    name := "e2e",
    commonSettings,
    libraryDependencies ++= Dependencies.http,
    // Not part of the normal test run — only executed explicitly via `e2e/test`
    Test / fork := true,
  )

// versola-tools: packages scripts/gen-env.scala as a plain JVM app instead
// of relying on scala-cli at image-build/run time (see docker/Dockerfile.tools).
// Deliberately NOT using commonSettings -- that pulls in Dependencies.core
// (ZIO, etc.), which gen-env.scala doesn't use and which would bloat the
// staged jar for no reason. Base directory is scripts/ itself (not a new
// top-level tools/ folder) and Compile/scalaSource points at that same
// directory, so gen-env.scala stays the single copy on disk, compiled by
// both `scala-cli run scripts/gen-env.scala` (local dev, see develop.md)
// and this sbt project (CI release staging) -- not a duplicated snapshot
// that could drift, the exact property the old Dockerfile.tools comments
// cared about preserving.
// Not part of `root`'s aggregate, same reasoning as `e2e` above: staged
// explicitly in CI (`sbt tools/stage`), not part of the default
// `sbt compile`/`sbt test` loop.
lazy val tools = project
  .in(file("scripts"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "tools",
    scalaVersion := "3.8.1",
    scalacOptions ++= Seq(
      "-deprecation",
      "-source:future",
      "-new-syntax",
      "-indent",
    ),
    // scripts/ also holds scala-cli's own build cache (.scala-build/,
    // possibly .bsp/) from `scala-cli run scripts/gen-env.scala` -- full
    // of old generated snapshots of this same file from past edits, with
    // top-level defs that collide with the real gen-env.scala once sbt
    // globs a whole directory recursively for sources. Tried excluding
    // .scala-build via `excludeFilter` first (sbt's default excludeFilter,
    // HiddenFileFilter, only recognizes the OS-level hidden attribute --
    // .scala-build isn't flagged hidden on Windows despite the leading
    // dot); that didn't take effect either (confirmed by hand: same
    // duplicate-definition errors regardless). Sidestepping the whole
    // recursive-discovery-plus-filter mechanism instead: there is exactly
    // one real source file here, so list it directly rather than pointing
    // at the directory and hoping nothing else in it gets swept up.
    Compile / unmanagedSourceDirectories := Seq(baseDirectory.value),
    Compile / unmanagedSources := Seq(baseDirectory.value / "gen-env.scala"),
    // Matches the synthetic object Scala 3 generates for gen-env.scala's
    // top-level `@main def genEnv(): Unit`.
    Compile / mainClass := Some("genEnv"),
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
    // Entry points and bootstrap wiring are not unit-testable in isolation, exclude from coverage.
    coverageExcludedFiles := ".*App.*|.*BootstrapService.*",
    semanticdbEnabled := true
  )

val CompileTest = "compile->compile;test->test"

