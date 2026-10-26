package versola.antifraud

import org.scalamock.stubs.Stub
import versola.oauth.client.model.TenantId
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object AntifraudDetectorsSpec extends UnitSpecBase:

  private val on = AntifraudDetectors.Settings.default.copy(enabled = true)

  private def build(repository: Stub[AntifraudProposalRepository], settings: AntifraudDetectors.Settings): ZIO[Scope, Nothing, AntifraudDetectors] =
    AntifraudDetectors.live.build.map(_.get[AntifraudDetectors]).provideSome[Scope](
      ZLayer.succeed[AntifraudProposalRepository](repository),
      ZLayer.succeed(settings),
    )

  private def quiet(repository: Stub[AntifraudProposalRepository]) =
    for
      _ <- repository.detectPrefixAnomalies.succeedsWith(Some(0))
      _ <- repository.detectFakeVerifies.succeedsWith(Some(0))
      _ <- repository.expireOpen.succeedsWith(0)
      _ <- repository.prune.succeedsWith(0)
    yield ()

  private val proposal = AntifraudProposal(
    id = UUID.fromString("018f2a4c-0000-7000-8000-000000000001"),
    tenantId = TenantId("tenant-a"),
    kind = ProposalKind.prefix_anomaly,
    valueType = AntifraudValueType.prefix,
    value = "4915",
    evidence = """{"requests": 150}""",
    suggestedAction = AntifraudListType.fake,
    ttl = 24.hours,
    status = ProposalStatus.open,
    createdAt = Instant.parse("2026-10-21T12:00:00Z"),
    decidedBy = None,
    decidedAt = None,
  )

  def spec = suite("AntifraudDetectors")(
    suite("the job")(
      test("does nothing while it is off, however long it waits") {
        val repository = stub[AntifraudProposalRepository]
        ZIO.scoped:
          for
            _ <- build(repository, AntifraudDetectors.Settings.default)
            _ <- TestClock.adjust(1.hour)
          yield assertTrue(
            repository.detectPrefixAnomalies.calls.isEmpty,
            repository.detectFakeVerifies.calls.isEmpty,
            repository.expireOpen.calls.isEmpty,
          )
      },
      test("runs every enabled detector once an interval, with the settings of each, and expires and prunes") {
        val repository = stub[AntifraudProposalRepository]
        ZIO.scoped:
          for
            _ <- quiet(repository)
            _ <- build(repository, on)
            before <- ZIO.succeed(repository.detectPrefixAnomalies.calls.size)
            _ <- TestClock.adjust(1.minute)
            _ <- TestClock.adjust(1.minute)
          yield assertTrue(
            before == 0,
            repository.detectPrefixAnomalies.calls.map(_._2) == List(on.prefixAnomaly, on.prefixAnomaly),
            repository.detectFakeVerifies.calls.map(_._2) == List(on.fakeVerify, on.fakeVerify),
            repository.expireOpen.calls.size == 2,
            repository.prune.calls.size == 2,
          )
      },
      test("leaves a detector that is off alone") {
        val repository = stub[AntifraudProposalRepository]
        ZIO.scoped:
          for
            _ <- quiet(repository)
            _ <- build(repository, on.copy(fakeVerify = on.fakeVerify.copy(enabled = false)))
            _ <- TestClock.adjust(1.minute)
          yield assertTrue(repository.detectPrefixAnomalies.calls.size == 1, repository.detectFakeVerifies.calls.isEmpty)
      },
      test("a detector that fails does not stop the other or the next run") {
        val repository = stub[AntifraudProposalRepository]
        ZIO.scoped:
          for
            _ <- quiet(repository)
            _ <- repository.detectPrefixAnomalies.returnsZIO(_ => ZIO.fail(RuntimeException("statement timeout")))
            _ <- build(repository, on)
            _ <- TestClock.adjust(1.minute)
            _ <- TestClock.adjust(1.minute)
          yield assertTrue(repository.detectPrefixAnomalies.calls.size == 2, repository.detectFakeVerifies.calls.size == 2)
      },
      test("passes the limits of the run to the detectors, and expires what has been open longer than it may be") {
        val repository = stub[AntifraudProposalRepository]
        ZIO.scoped:
          for
            _ <- quiet(repository)
            // Before the job starts: a clock set ahead of a sleeping loop runs it once per interval skipped.
            _ <- TestClock.setTime(Instant.parse("2026-10-21T12:00:00Z"))
            _ <- build(repository, on)
            _ <- TestClock.adjust(1.minute)
            run = repository.detectFakeVerifies.calls.head._3
            expired = repository.expireOpen.calls.head
          yield assertTrue(
            run == AntifraudDetectors.Run(
              settle = on.settle,
              statementTimeout = on.statementTimeout,
              maxProposals = on.maxProposals,
              candidates = on.candidates,
              cooldown = on.cooldown,
              ttl = on.ttl,
            ),
            expired._1 == Instant.parse("2026-10-21T12:01:00Z").minusSeconds(on.openFor.getSeconds),
            expired._2 == on.batchSize,
          )
      },
    ),
    suite("a proposal as a list entry")(
      test("is an entry of the suggested type, for the tenant, that expires after the ttl, made by the detector and not in shadow") {
        val now = Instant.parse("2026-10-22T08:00:00Z")
        val entry = proposal.toNewListEntry(now)
        assertTrue(
          entry == NewListEntry(
            listType = AntifraudListType.fake,
            valueType = AntifraudValueType.prefix,
            value = "4915",
            tenantId = Some(TenantId("tenant-a")),
            expiresAt = Some(Instant.parse("2026-10-23T08:00:00Z")),
            reason = Some("""prefix_anomaly: {"requests": 150}"""),
            source = "detector:prefix_anomaly",
            shadow = false,
          ),
        )
      },
      test("is never global") {
        assertTrue(proposal.toNewListEntry(Instant.EPOCH).tenantId.isDefined)
      },
    ),
  )
