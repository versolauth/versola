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
    // Every spec in here truncates the same `revocations` table between tests,
    // so two of them running at once would clear each other's rows.
    Test / parallelExecution := false,
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
    Test / parallelExecution := false,
    sbtForkSettings,
  ).dependsOn(
    central % CompileTest,
    `util-postgres` % CompileTest
  )

// Standalone migration runner shipped inside versola-tools (see docker/Dockerfile.tools and
// docker/versola-tools/entrypoint.sh's "migrate" dispatch branch), backing `versola migrate`.
// Not part of `root`'s aggregate, same reasoning as `tools`/`e2e` below -- staged explicitly in
// CI (`sbt migrate-tool/stage`), not part of the default `sbt compile`/`sbt test` loop.
//
// Deliberately does NOT use `commonSettings` (unlike every other project here except `tools`,
// which has the same reason -- see its own comment) and does NOT `.dependsOn(util,
// util-postgres)`. An earlier version did both, to reuse PostgresHikariDataSource's ZIO-based
// Flyway setup instead of copying it -- but `commonSettings` alone pulls in the whole
// `Dependencies.core` list (ZIO, HikariCP, WebAuthn, JWT, mail, ...) regardless of `dependsOn`,
// and `util` additionally drags in CEL, transitively pulling okhttp/okio with
// Automatic-Module-Name metadata `jdeps` can't resolve. That combination is what broke
// jlink for this image ("Module okio not found, required by okhttp3" -- see
// docker/Dockerfile.tools' git history). This is a one-shot batch job that only ever needs
// Flyway, the Postgres driver, and a HOCON parser to read each service's already-generated
// .conf file -- MigrateTool.scala now has its own small, synchronous copy of the Flyway
// configuration instead (see its own comment on why that copy is intentional, not an
// oversight), built only from the minimal `Dependencies.migrateTool` list below.
lazy val migrateTool = project
  .in(file("migrate-tool"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "migrate-tool",
    scalaVersion := "3.8.1",
    scalacOptions ++= Seq(
      "-deprecation",
      "-source:future",
      "-new-syntax",
      "-indent",
    ),
    libraryDependencies ++= Dependencies.migrateTool,
    Compile / mainClass := Some("versola.migrate.MigrateTool"),
  )

lazy val e2e = project
  .in(file("e2e"))
  .settings(
    name := "e2e",
    commonSettings,
    libraryDependencies ++= Dependencies.http,
    // Not part of the normal test run — only executed explicitly via `e2e/test`
    Test / fork := true,
    // Every spec drives the same running auth/central/edge stack, and central answers reads
    // from caches a Postgres notification refreshes just after the write commits. Run in
    // parallel, suites read each other's load as lag and see a write that has not landed yet.
    Test / parallelExecution := false,
    // One JVM per spec. zio-test merges the `bootstrap` layers of every spec it runs in a JVM
    // into a single shared environment, so two specs asking for the same service — the edge
    // specs all build an `EdgeFixture` — would silently be handed one another's fixture.
    Test / testGrouping := (Test / definedTests).value.map { spec =>
      Tests.Group(
        name = spec.name,
        tests = Seq(spec),
        runPolicy = Tests.SubProcess((Test / forkOptions).value),
      )
    },
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

// versola-loadgen: coordinator + driver for the load emulator (see
// versola-loadgen-dev-spec.md). Depends on `util`/`util-postgres` for `VersolaApp` (diagnostics
// port, `/liveness`, `/readiness`, graceful shutdown) and `PostgresHikariDataSource` -- the
// emulator's own state store, not the SUT's. Not part of `root`'s aggregate, same reasoning as
// `e2e`/`tools` above: staged explicitly (`sbt loadgen/stage`), not part of the default
// `sbt compile`/`sbt test` loop. ci-cd.yml does NOT yet compile or stage this project -- that
// change to the "Compile" step needs the `workflow` token scope, tracked on #268; until it lands
// nothing here is validated by CI (the same gap `tools` closed for itself by naming itself in
// that step).
lazy val loadgen = project
  .in(file("loadgen"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "loadgen",
    commonSettings,
    libraryDependencies ++= Dependencies.http ++ Dependencies.database.postgres ++ Seq(
      "org.hdrhistogram" % "HdrHistogram" % Versions.hdrHistogram,
    ),
    Compile / mainClass := Some("versola.loadgen.Main"),
    // Not aggregated by root, so `sbt coverage test coverageReport coverageAggregate`
    // (the "Run tests with coverage" CI step) never touches this project anyway -- explicit
    // here so that stays true even if someone runs `loadgen/coverage loadgen/test` directly.
    coverageEnabled := false,
  )
  .dependsOn(
    util % CompileTest,
    `util-postgres` % CompileTest,
  )

// versola-mockapi: the load emulator's mock protected-resource backend (see
// versola-loadgen-dev-spec.md §9). Deliberately does NOT depend on `util` and deliberately does
// NOT use `commonSettings` -- both pull in `Dependencies.core` regardless of `dependsOn` (see
// `migrateTool`'s comment above for the same reasoning), none of which a pure delay generator has
// any use for. Measured, not assumed: staging with `Dependencies.http` alone gives 89 jars, and
// drops BouncyCastle, the WebAuthn server, nimbus-jose-jwt, Flyway, HikariCP, magnum, the
// Postgres driver, CEL, json-schema-validator, libphonenumber and angus-mail. Note what it does
// NOT drop: `Dependencies.http` carries the OpenTelemetry SDK and the OTLP exporter itself, so
// okhttp/okio are on this classpath either way -- the win here is that no middleware uses them
// per request, not that they're absent.
//
// The classpath is only half of it: depending on `util` is what makes `VersolaApp` available, and
// `VersolaApp` mounts `Observability.middleware` in front of every route -- a span, the RED
// counters, request/response serialisation and one unfiltered `receive-http` JSON log line per
// request. At ~8,400 rps that is a constant latency bias on the process the whole campaign's
// latency numbers are measured against, so `mockapi` owns its own (much smaller) boot sequence
// instead. The cost of that choice is real and worth stating: no tracing, no `/metrics`, no
// shared graceful-shutdown behaviour, and a `/liveness`+`/readiness` surface that has to stay
// correct here on its own. Not part of `root`'s aggregate, and -- like `loadgen` above -- not yet
// named in ci-cd.yml's "Compile" step either; see that comment.
lazy val mockapi = project
  .in(file("mockapi"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "mockapi",
    scalaVersion := "3.8.1",
    scalacOptions ++= Seq(
      "-deprecation",
      "-source:future",
      "-new-syntax",
      "-indent",
    ),
    libraryDependencies ++= Dependencies.http ++ Seq(
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Compile / mainClass := Some("versola.mockapi.Main"),
    // Same reasoning as loadgen's coverageEnabled above.
    coverageEnabled := false,
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

