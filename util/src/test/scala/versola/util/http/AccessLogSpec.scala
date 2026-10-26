package versola.util.http

import zio.json.*
import zio.test.*

object AccessLogSpec extends ZIOSpecDefault:
  private val sampled = AccessLogSettings(AccessLogMode.Sampled, slowMillis = 500, successSampleRate = 0.05)

  // 32 hex characters, like a real trace id
  private def traceId(i: Int): String = f"${i * 2654435761L}%016x${i.toLong * 40503L + 1}%016x"

  private def share(settings: AccessLogSettings, n: Int): Double =
    (0 until n).count(i => settings.logReceive(200, failed = false, elapsedMillis = 1, traceId(i))).toDouble / n

  def spec = suite("AccessLog")(
    suite("logReceive")(
      test("full logs everything") {
        val full = sampled.copy(mode = AccessLogMode.Full)
        assertTrue(full.logReceive(200, failed = false, 1, traceId(1)))
      },
      test("sampled always logs errors and failures, whatever the trace id") {
        val none = sampled.copy(successSampleRate = 0.0)
        assertTrue(
          (0 until 200).forall(i => none.logReceive(400, failed = false, 1, traceId(i))),
          (0 until 200).forall(i => none.logReceive(503, failed = false, 1, traceId(i))),
          (0 until 200).forall(i => none.logReceive(200, failed = true, 1, traceId(i))),
          (0 until 200).forall(i => none.logReceive(302, failed = true, 1, traceId(i))),
        )
      },
      test("sampled always logs a slow request and not one just under the threshold") {
        val none = sampled.copy(successSampleRate = 0.0)
        assertTrue(
          none.logReceive(200, failed = false, 500, traceId(1)),
          none.logReceive(200, failed = false, 9000, traceId(2)),
          !none.logReceive(200, failed = false, 499, traceId(1)),
        )
      },
      test("sampled decides by trace id alone: same trace, same answer, at every elapsed time below the threshold") {
        val ids = (0 until 500).map(traceId)
        assertTrue(
          ids.forall { id =>
            val first = sampled.logReceive(200, failed = false, 1, id)
            (1L until 400L by 57L).forall(ms => sampled.logReceive(200, failed = false, ms, id) == first)
          },
        )
      },
      test("a trace sampled at a low rate is also sampled at every higher rate") {
        val ids = (0 until 2000).map(traceId)
        assertTrue(ids.forall { id =>
          !sampled.copy(successSampleRate = 0.05).logReceive(200, false, 1, id) ||
          sampled.copy(successSampleRate = 0.2).logReceive(200, false, 1, id)
        })
      },
      test("the sampled share is close to the rate") {
        val observed = share(sampled, 50000)
        assertTrue(observed > 0.04, observed < 0.06)
      },
      test("rate 0 logs no success and rate 1 logs every one") {
        assertTrue(
          share(sampled.copy(successSampleRate = 0.0), 1000) == 0.0,
          share(sampled.copy(successSampleRate = 1.0), 1000) == 1.0,
        )
      },
      test("without a trace id requests are sampled at random at about the rate") {
        val s = sampled.copy(successSampleRate = 0.5)
        val kept = (0 until 4000).count(_ => s.logReceive(200, false, 1, "00000000000000000000000000000000"))
        assertTrue(kept > 1700, kept < 2300)
      },
      test("off logs failures and slow requests only") {
        val off = sampled.copy(mode = AccessLogMode.Off, successSampleRate = 1.0)
        assertTrue(
          !off.logReceive(200, failed = false, 1, traceId(1)),
          off.logReceive(200, failed = false, 60000, traceId(1)),
          off.logReceive(404, failed = false, 1, traceId(1)),
          off.logReceive(200, failed = true, 1, traceId(1)),
        )
      },
    ),
    suite("parse")(
      test("defaults are full, 500 ms, 1 in 20") {
        val s = AccessLogSettings.parse(None, None, None)
        assertTrue(s.mode == AccessLogMode.Full, s.slowMillis == 500, s.successSampleRate == 0.05)
      },
      test("a service default replaces full when the mode is unset, and an explicit mode still wins") {
        val edge = AccessLogSettings.parse(None, None, None, AccessLogMode.Off)
        val explicit = AccessLogSettings.parse(Some("full"), None, None, AccessLogMode.Off)
        val invalid = AccessLogSettings.parse(Some("verbose"), None, None, AccessLogMode.Off)
        assertTrue(edge.mode == AccessLogMode.Off, explicit.mode == AccessLogMode.Full, invalid.mode == AccessLogMode.Off)
      },
      test("reads valid values") {
        val s = AccessLogSettings.parse(Some(" Sampled "), Some("250"), Some("0.1"))
        assertTrue(s.mode == AccessLogMode.Sampled, s.slowMillis == 250, s.successSampleRate == 0.1)
      },
      test("an invalid value falls back to its default") {
        val s = AccessLogSettings.parse(Some("verbose"), Some("-1"), Some("1.5"))
        assertTrue(s == AccessLogSettings.default, AccessLogSettings.parse(None, Some("x"), Some("nan")) == AccessLogSettings.default)
      },
      test("isSlow is inclusive of the threshold") {
        assertTrue(sampled.isSlow(500), !sampled.isSlow(499))
      },
    ),
    suite("AccessLogPolicy")(
      test("an empty policy leaves the base alone and any field patches only itself") {
        assertTrue(
          AccessLogPolicy.empty.applyTo(sampled) == sampled,
          AccessLogPolicy(mode = Some("off")).applyTo(sampled) == sampled.copy(mode = AccessLogMode.Off),
          AccessLogPolicy(slowMillis = Some(100)).applyTo(sampled) == sampled.copy(slowMillis = 100),
          AccessLogPolicy(successSampleRate = Some(0.5)).applyTo(sampled) == sampled.copy(successSampleRate = 0.5),
        )
      },
      test("validate rejects an unknown mode and out-of-range numbers") {
        assertTrue(
          AccessLogPolicy(mode = Some("loud")).validate.isLeft,
          AccessLogPolicy(slowMillis = Some(-1)).validate.isLeft,
          AccessLogPolicy(slowMillis = Some(600001)).validate.isLeft,
          AccessLogPolicy(successSampleRate = Some(1.01)).validate.isLeft,
          AccessLogPolicy(successSampleRate = Some(-0.1)).validate.isLeft,
          AccessLogPolicy(Some("sampled"), Some(500), Some(0.05)).validate.isRight,
          AccessLogPolicy.empty.validate.isRight,
        )
      },
      test("round-trips through JSON, absent fields staying absent") {
        val policy = AccessLogPolicy(mode = Some("sampled"), successSampleRate = Some(0.1))
        assertTrue(
          policy.toJson.fromJson[AccessLogPolicy] == Right(policy),
          "{}".fromJson[AccessLogPolicy] == Right(AccessLogPolicy.empty),
        )
      },
    ),
  )
