package versola.central.antifraud

import versola.central.users.AuthClient
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

object AntifraudStatusServiceSpec extends UnitSpecBase:
  private def statusOf(version: Long, enabled: Boolean = true) = AntifraudInstanceStatus(
    decision = AntifraudDecisionStatus(enabled = enabled),
    lists = AntifraudListsStatus(enabled = true, snapshotAgeSeconds = Some(1), size = 4, skipped = 0, lastRefreshError = None),
    tuning = AntifraudTuningStatus(available = true, version = Some(version), ageSeconds = Some(2), lastRefreshError = None),
  )

  def spec = suite("AntifraudStatusService")(
    test("the status is the configured auth's, with the status of every cell next to it") {
      val auth = stub[AuthClient]
      val cells = List(
        AntifraudCellStatus(cell = "kz-1", status = Some(statusOf(version = 6)), error = None),
        AntifraudCellStatus(cell = "kz-2", status = None, error = Some("connection refused")),
      )
      for
        _ <- auth.antifraudStatus.succeedsWith(statusOf(version = 7, enabled = false))
        _ <- auth.antifraudStatusOfCells.succeedsWith(cells)
        status <- AntifraudStatusService.Impl(auth).status
      yield assertTrue(
        status == AntifraudStatusResponse(
          decision = AntifraudDecisionStatus(enabled = false),
          lists = statusOf(version = 7).lists,
          tuning = statusOf(version = 7).tuning,
          cells = cells,
        ),
      )
    },
    test("the status has no cells where none is registered") {
      val auth = stub[AuthClient]
      for
        _ <- auth.antifraudStatus.succeedsWith(statusOf(version = 7))
        _ <- auth.antifraudStatusOfCells.succeedsWith(Nil)
        status <- AntifraudStatusService.Impl(auth).status
      yield assertTrue(status.cells.isEmpty, status.tuning.version.contains(7L))
    },
    test("a refusal of the configured auth is the status's, and the cells are not waited for") {
      val auth = stub[AuthClient]
      for
        _ <- auth.antifraudStatus.failsWith(AntifraudListError.Invalid("not today"))
        _ <- auth.antifraudStatusOfCells.succeedsWith(Nil)
        failed <- AntifraudStatusService.Impl(auth).status.either
      yield assertTrue(failed == Left(AntifraudListError.Invalid("not today")))
    },
    test("the rule catalogue is auth's, passed on") {
      val auth = stub[AuthClient]
      for
        _ <- auth.antifraudRules.succeedsWith(AntifraudRulesResponse(List("velocity_phone_1m")))
        rules <- AntifraudStatusService.Impl(auth).rules
      yield assertTrue(rules == AntifraudRulesResponse(List("velocity_phone_1m")))
    },
  )
