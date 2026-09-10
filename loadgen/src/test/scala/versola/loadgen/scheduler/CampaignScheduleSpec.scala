package versola.loadgen.scheduler

import versola.loadgen.config.CampaignConfig
import versola.loadgen.config.CampaignPhaseConfig
import versola.loadgen.config.DiurnalConfig
import versola.loadgen.config.RegistrationConfig
import zio.durationInt
import zio.test.*

import java.time.Instant

/** `rate(t) = base × scale(phase) × diurnal(hourOfDay)` (§7.3) over the phase list of §5. */
object CampaignScheduleSpec extends ZIOSpecDefault:

  private val startedAt = Instant.parse("2026-09-10T00:00:00Z")

  private def phase(name: String, minutes: Int, scale: Option[Double], from: Option[Double], to: Option[Double]) =
    CampaignPhaseConfig(name = name, duration = minutes.minutes, scale = scale, scaleFrom = from, scaleTo = to)

  private def campaign(phases: List[CampaignPhaseConfig], diurnal: Boolean): CampaignConfig =
    CampaignConfig(
      name = "c3-10m-steady",
      phases = phases,
      diurnal = DiurnalConfig(enabled = diurnal, peakFactor = 3.0, peakHour = 20, timezone = "UTC"),
      registration = RegistrationConfig(enabled = false, target = 1_000_000L, duration = 72.hours),
    )

  private val phases = List(
    phase("warmup", 15, Some(0.1), None, None),
    phase("ramp", 30, None, Some(0.1), Some(1.0)),
    phase("steady", 60, Some(1.0), None, None),
  )

  def spec = suite("CampaignSchedule")(
    suite("phases")(
      test("resolves phases back to back and picks the scale by which fields are set") {
        val schedule = CampaignSchedule.from(campaign(phases, diurnal = false), startedAt).toOption.get
        assertTrue(
          schedule.phases.map(_.name) == Vector("warmup", "ramp", "steady"),
          schedule.phases.map(_.startsAt) == Vector(0.minutes, 15.minutes, 45.minutes),
          schedule.totalDuration == 105.minutes,
          schedule.phaseAt(startedAt.plusSeconds(60)).map(_.name) == Some("warmup"),
          schedule.phaseAt(startedAt.plusSeconds(30 * 60)).map(_.name) == Some("ramp"),
          schedule.scaleAt(startedAt) == 0.1,
        )
      },
      test("interpolates a ramp linearly across its own duration") {
        val schedule = CampaignSchedule.from(campaign(phases, diurnal = false), startedAt).toOption.get
        val midRamp = startedAt.plusSeconds((15 + 15) * 60)
        assertTrue(
          math.abs(schedule.scaleAt(midRamp) - 0.55) < 1e-12,
          math.abs(schedule.scaleAt(startedAt.plusSeconds(15 * 60)) - 0.1) < 1e-12,
          math.abs(schedule.scaleAt(startedAt.plusSeconds(45 * 60)) - 1.0) < 1e-12,
        )
      },
      test("generates nothing before the campaign starts or after it ends") {
        val schedule = CampaignSchedule.from(campaign(phases, diurnal = false), startedAt).toOption.get
        assertTrue(
          schedule.phaseAt(startedAt.minusSeconds(1)) == None,
          schedule.scaleAt(startedAt.minusSeconds(1)) == 0.0,
          schedule.scaleAt(startedAt.plusSeconds(105 * 60)) == 0.0,
          schedule.rateAt(2_650.0, startedAt.plusSeconds(105 * 60)) == 0.0,
        )
      },
    ),
    suite("rate")(
      test("multiplies base by phase scale and by the diurnal envelope") {
        val flat = CampaignSchedule.from(campaign(phases, diurnal = false), startedAt).toOption.get
        val shaped = CampaignSchedule.from(campaign(phases, diurnal = true), startedAt).toOption.get
        val steady = startedAt.plusSeconds(50 * 60)
        val peak = startedAt.plusSeconds(20 * 3600 + 50 * 60)
        assertTrue(
          flat.rateAt(2_650.0, steady) == 2_650.0,
          // The ratio between the shaped and the flat rate is exactly the *normalised* envelope,
          // never the raw peak factor: at 00:50 against a 20:00 peak that is 1.02, because the
          // trough of this envelope is 08:00 (the antipode) and not midnight.
          math.abs(shaped.rateAt(2_650.0, steady) / 2_650.0 - shaped.diurnal.at(steady)) < 1e-9,
          math.abs(shaped.rateAt(2_650.0, steady) - 2_711.42) < 0.01,
          shaped.diurnal.atHour(8.0) < 1.0 && shaped.diurnal.atHour(20.0) > 1.0,
          // ... but the peak instant falls outside the 105-minute campaign, so it generates
          // nothing: the envelope shapes the campaign, it does not extend it.
          shaped.rateAt(2_650.0, peak) == 0.0,
        )
      },
    ),
    suite("validation")(
      test("rejects a phase that sets neither scale nor both ramp ends") {
        val broken = List(phase("warmup", 15, None, Some(0.1), None))
        assertTrue(CampaignSchedule.from(campaign(broken, diurnal = false), startedAt).isLeft)
      },
      test("rejects a phase that sets both a flat scale and a ramp") {
        val broken = List(phase("warmup", 15, Some(0.5), Some(0.1), Some(1.0)))
        assertTrue(CampaignSchedule.from(campaign(broken, diurnal = false), startedAt).isLeft)
      },
      test("rejects an empty phase list and an unusable diurnal block") {
        assertTrue(
          CampaignSchedule.from(campaign(Nil, diurnal = false), startedAt).isLeft,
          CampaignSchedule
            .from(campaign(phases, diurnal = true).copy(diurnal = DiurnalConfig(true, 3.0, 20, "Mars/Olympus")), startedAt)
            .isLeft,
        )
      },
    ),
  )
