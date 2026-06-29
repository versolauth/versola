package versola.util.postgres

import zio.*
import zio.metrics.*
import zio.test.*

object DbMetricsSpec extends ZIOSpecDefault:

  private def histogramCount(repository: String, operation: String, outcome: String): UIO[Long] =
    Metric
      .histogram("db_client_operation_duration_seconds", DbMetrics.boundaries)
      .tagged(
        MetricLabel("repository", repository),
        MetricLabel("operation", operation),
        MetricLabel("db_system", "postgresql"),
        MetricLabel("outcome", outcome),
      )
      .value
      .map(_.count)

  // Repo-shaped helper: the call-site trace must resolve `repository` to this object's simple name.
  private def autoDerivedOp: Task[Int] =
    DbMetrics.measured("auto-op")(ZIO.succeed(1))

  def spec = suite("DbMetrics")(
    suite("repositoryName")(
      test("derives the simple class name from a method location") {
        val trace =
          Trace.apply("versola.configuration.themes.PostgresThemeRepository.getAll", "PostgresThemeRepository.scala", 10)
        assertTrue(DbMetrics.repositoryName(trace) == "versola.configuration.themes.PostgresThemeRepository.getAll")
      },
      test("strips a trailing $ from object owners") {
        val trace = Trace.apply("versola.configuration.themes.ThemeQueries$.getAll", "ThemeQueries.scala", 5)
        assertTrue(DbMetrics.repositoryName(trace) == "versola.configuration.themes.ThemeQueries.getAll")
      },
      test("falls back to unknown for the empty trace") {
        assertTrue(DbMetrics.repositoryName(Trace.empty) == "unknown")
      },
    ),
    test("records a success outcome with the derived repository and given operation") {
      val trace = Trace.apply("versola.test.SuccessRepo.run", "SuccessRepo.scala", 1)
      for
        _ <- DbMetrics.measured("success-op")(ZIO.succeed(()))(using trace)
        count <- histogramCount("versola.test.SuccessRepo.run", "success-op", "success")
      yield assertTrue(count == 1L)
    },
    test("records a failure outcome and re-raises the original error") {
      val trace = Trace.apply("versola.test.FailureRepo.run", "FailureRepo.scala", 1)
      val boom = new RuntimeException("boom")
      for
        exit <- DbMetrics.measured("failure-op")(ZIO.fail(boom))(using trace).exit
        count <- histogramCount("versola.test.FailureRepo.run", "failure-op", "failure")
      yield assertTrue(exit == Exit.fail(boom), count == 1L)
    },
    test("auto-derives the repository from the enclosing class") {
      for
        result <- autoDerivedOp
        count <- histogramCount("versola.util.postgres.DbMetricsSpec.autoDerivedOp", "auto-op", "success")
      yield assertTrue(result == 1, count == 1L)
    },
  )
