package versola.loadgen.metrics

import versola.loadgen.protocol.{ProtocolError, RefreshRejection}
import zio.http.Status
import zio.json.*
import zio.test.*

/** The taxonomy's whole job is the outcome/failure split, so this spec asserts the split for
  * every constructor of [[ProtocolError]] by name. Listed exhaustively rather than derived: a case
  * added to the ADT without a decision about which side it lands on should show up here as a
  * missing test, not be swept along by a generic property.
  */
object ErrorTaxonomySpec extends ZIOSpecDefault:

  private val transport = ProtocolError.Transport(RuntimeException("connection reset"))
  private val unexpectedStatus =
    ProtocolError.UnexpectedStatus(Set(Status.Ok), Status.InternalServerError, "/resources/core/accounts")
  private val malformed = ProtocolError.MalformedResponse("/token", "no access_token in body")
  private val stepUp = ProtocolError.StepUpRequired(List("otp-level"), "/resources/pay/p2p")
  private val forbidden = ProtocolError.Forbidden("/resources/pay/templates")
  private val unauthorized = ProtocolError.Unauthorized("/resources/core/cards")
  private val refreshRejected = ProtocolError.RefreshRejected(RefreshRejection.AlreadyExchanged)

  private val everyError = List(transport, unexpectedStatus, malformed, stepUp, forbidden, unauthorized, refreshRejected)

  def spec = suite("ErrorTaxonomy")(
    test("classifies step-up, forbidden and unauthorized as planned outcomes") {
      assertTrue(
        StepOutcome.of(stepUp) == StepOutcome.Planned(PlannedOutcome.StepUp),
        StepOutcome.of(forbidden) == StepOutcome.Planned(PlannedOutcome.Forbidden),
        StepOutcome.of(unauthorized) == StepOutcome.Planned(PlannedOutcome.Unauthorized),
      )
    },
    test("classifies transport, status, malformed and refresh rejection as failures") {
      assertTrue(
        StepOutcome.of(transport) == StepOutcome.Failed(FailedOutcome.Transport),
        StepOutcome.of(unexpectedStatus) == StepOutcome.Failed(FailedOutcome.UnexpectedStatus),
        StepOutcome.of(malformed) == StepOutcome.Failed(FailedOutcome.Malformed),
        StepOutcome.of(refreshRejected) == StepOutcome.Failed(FailedOutcome.RefreshRejected),
      )
    },
    test("classifies a successful result as ok") {
      assertTrue(StepOutcome.of(Right(())) == StepOutcome.ok, StepOutcome.ok.label == "ok")
    },
    test("no protocol error is left unclassified") {
      val classified = everyError.map(StepOutcome.of)
      assertTrue(classified.size == everyError.size, classified.forall(_.label.nonEmpty))
    },
    test("planned outcomes never consume the error budget") {
      val taxonomy = everyError.foldLeft(ErrorTaxonomy.empty)((acc, error) => acc.record(StepOutcome.of(error)))
      // Seven errors in, three of them planned: the budget must see exactly the other four.
      assertTrue(
        taxonomy.total == 7L,
        taxonomy.plannedTotal == 3L,
        taxonomy.budgetConsumed == 4L,
        taxonomy.plannedCount(PlannedOutcome.StepUp) == 1L,
        taxonomy.failedCount(FailedOutcome.RefreshRejected) == 1L,
      )
    },
    test("a campaign of nothing but step-ups and 403s consumes no budget at all") {
      val taxonomy = List.fill(500)(stepUp).appendedAll(List.fill(120)(forbidden))
        .foldLeft(ErrorTaxonomy.empty)((acc, error) => acc.record(StepOutcome.of(error)))
      assertTrue(taxonomy.total == 620L, taxonomy.budgetConsumed == 0L, taxonomy.budgetRatio == 0.0)
    },
    test("the budget ratio is failures over all steps") {
      val taxonomy = ErrorTaxonomy.empty
        .recordMany(StepOutcome.ok, 990L)
        .recordMany(StepOutcome.Planned(PlannedOutcome.StepUp), 500L)
        .recordMany(StepOutcome.Failed(FailedOutcome.Transport), 10L)
      assertTrue(taxonomy.total == 1500L, taxonomy.budgetRatio == 10.0 / 1500.0)
    },
    test("an empty taxonomy has a zero ratio rather than a NaN") {
      assertTrue(ErrorTaxonomy.empty.budgetRatio == 0.0, ErrorTaxonomy.empty.total == 0L)
    },
    test("per-driver tallies add") {
      val one = ErrorTaxonomy.empty.recordMany(StepOutcome.ok, 3L).recordMany(
        StepOutcome.Failed(FailedOutcome.Transport),
        1L,
      )
      val two = ErrorTaxonomy.empty.recordMany(StepOutcome.ok, 5L).recordMany(
        StepOutcome.Planned(PlannedOutcome.Forbidden),
        2L,
      )
      val merged = ErrorTaxonomy.mergeAll(List(one, two))
      assertTrue(
        merged.plannedCount(PlannedOutcome.Ok) == 8L,
        merged.plannedCount(PlannedOutcome.Forbidden) == 2L,
        merged.budgetConsumed == 1L,
        merged == two.merge(one),
      )
    },
    test("round-trips through JSON keyed by the Prometheus label values") {
      val taxonomy = ErrorTaxonomy.empty
        .recordMany(StepOutcome.Planned(PlannedOutcome.StepUp), 4L)
        .recordMany(StepOutcome.Failed(FailedOutcome.UnexpectedStatus), 2L)
      val json = taxonomy.toJson
      assertTrue(
        json.contains("\"stepup\":4"),
        json.contains("\"unexpected_status\":2"),
        json.fromJson[ErrorTaxonomy] == Right(taxonomy),
      )
    },
    test("collapses the refresh rejection reason to a bounded set of labels") {
      assertTrue(
        RefreshRejectionLabel.of(RefreshRejection.AlreadyExchanged) == "already_exchanged",
        RefreshRejectionLabel.of(RefreshRejection.SessionRevoked) == "session_revoked",
        RefreshRejectionLabel.of(RefreshRejection.Expired) == "expired",
        RefreshRejectionLabel.of(RefreshRejection.Unknown("invalid_grant: whatever the SUT said")) == "unknown",
      )
    },
  )
