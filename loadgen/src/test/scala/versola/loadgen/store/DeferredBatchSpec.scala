package versola.loadgen.store

import zio.Chunk
import zio.test.*

import java.time.Instant

object DeferredBatchSpec extends ZIOSpecDefault:

  private val t0 = Instant.parse("2026-09-10T20:00:00Z")

  def spec = suite("DeferredBatch.coalesce")(
    test("keeps only the latest last_seen_at per user") {
      val batch = DeferredBatch.coalesce(
        Chunk(
          DeferredUpdate.UserSeen(1L, t0),
          DeferredUpdate.UserSeen(2L, t0.plusSeconds(1)),
          DeferredUpdate.UserSeen(1L, t0.plusSeconds(5)),
        )
      )
      assertTrue(
        batch.userTouches == Chunk(UserTouch(1L, t0.plusSeconds(5)), UserTouch(2L, t0.plusSeconds(1)))
      )
    },
    test("keeps only the latest access_expires_at per session") {
      val batch = DeferredBatch.coalesce(
        Chunk(
          DeferredUpdate.SessionTouched(SessionTouch(7L, t0.plusSeconds(900))),
          DeferredUpdate.SessionTouched(SessionTouch(8L, t0.plusSeconds(30))),
          DeferredUpdate.SessionTouched(SessionTouch(7L, t0.plusSeconds(1800))),
        )
      )
      assertTrue(
        batch.sessionTouches == Chunk(
          SessionTouch(7L, t0.plusSeconds(1800)),
          SessionTouch(8L, t0.plusSeconds(30)),
        )
      )
    },
    test("keeps every event in arrival order -- the sample is a log, not a state") {
      val first = EventRow(t0, 1L, "mobile-refresh", "token", "ok", 12)
      val second = EventRow(t0.plusSeconds(1), 1L, "mobile-refresh", "token", "ok", 15)
      val batch = DeferredBatch.coalesce(
        Chunk(DeferredUpdate.EventSampled(first), DeferredUpdate.EventSampled(second))
      )
      assertTrue(batch.events == Chunk(first, second))
    },
    test("an empty window coalesces to an empty batch") {
      assertTrue(DeferredBatch.coalesce(Chunk.empty).isEmpty)
    },
  )
