package versola.loadgen.metrics

import zio.json.JsonCodec
import zio.{UIO, ZIO}

import java.util.concurrent.atomic.AtomicReference

/** What the SUT actually said about the tokens it issued, as opposed to what the emulator was
  * configured to believe about them.
  *
  * Three different numbers in this codebase are called the access-token TTL: `SessionConfig`'s,
  * which is only the emulator's belief and decides whether a session plans an extra refresh;
  * `CampaignBlueprint`'s, which is what `loadgen provision` *wrote* into central, one value for
  * all four clients; and `expires_in` on the `/token` response, which is the only one that
  * describes the system under test. The report's header needs the third, and needs it per client,
  * because a divergence between two clients is invisible in any average.
  *
  * Sets rather than counts: the question is "which values did this campaign ever see", and one
  * client that answered 900 for ten hours and 300 for one minute is a finding, not a rounding
  * error. Sets also merge across drivers and across a driver restart by union, so nothing here
  * has the double-counting problem the cumulative counters in [[versola.loadgen.coordinator.DriverVitals]]
  * have to be carried across restarts to avoid.
  *
  * @param responsesMissingTokenType
  *   RFC 6749 §5.1 makes `token_type` REQUIRED. An omission is therefore a defect in the SUT
  *   rather than an absence of information, and one the report has to be able to state --
  *   otherwise a run where half the responses carried no type reads exactly like a run where
  *   every one of them said `Bearer`.
  */
case class TokenObservations(
    accessTokenTtlsByClient: Map[String, Set[Long]],
    tokenTypes: Set[String],
    responsesMissingTokenType: Boolean,
) derives JsonCodec:

  def merge(other: TokenObservations): TokenObservations =
    TokenObservations(
      accessTokenTtlsByClient = other.accessTokenTtlsByClient.foldLeft(accessTokenTtlsByClient):
        case (accumulated, (clientId, ttls)) =>
          accumulated.updatedWith(clientId)(existing => Some(existing.getOrElse(Set.empty) ++ ttls)),
      tokenTypes = tokenTypes ++ other.tokenTypes,
      responsesMissingTokenType = responsesMissingTokenType || other.responsesMissingTokenType,
    )

object TokenObservations:
  val empty: TokenObservations = TokenObservations(Map.empty, Set.empty, false)

  def mergeAll(all: Iterable[TokenObservations]): TokenObservations =
    all.foldLeft(empty)((accumulated, one) => accumulated.merge(one))

/** The process's running [[TokenObservations]], recorded where the `/token` response is decoded.
  *
  * Process-global, and an `AtomicReference` rather than a `Ref`, for the same reason
  * [[versola.loadgen.protocol.InflightRequests]] is: every protocol client in the driver shares
  * one connection pool and one campaign, so what the SUT answers is a property of the process.
  *
  * Unlike that counter this is not on the hot path of every hop -- only of the token endpoint --
  * and it saturates within the first few seconds of a run, after which every update is a no-op.
  * The containment check before the CAS is what keeps it that way: without it, a steady campaign
  * would contend on this reference several thousand times a second to write back a value it
  * already held.
  */
object TokenObserver:

  private val observed = AtomicReference(TokenObservations.empty)

  def current: UIO[TokenObservations] = ZIO.succeed(observed.get)

  def record(clientId: String, expiresInSeconds: Long, tokenType: Option[String]): Unit =
    val seen = observed.get
    val knownTtl = seen.accessTokenTtlsByClient.get(clientId).exists(_.contains(expiresInSeconds))
    val knownType = tokenType.fold(seen.responsesMissingTokenType)(seen.tokenTypes.contains)
    if !knownTtl || !knownType then
      observed.updateAndGet: current =>
        current.merge(
          TokenObservations(
            accessTokenTtlsByClient = Map(clientId -> Set(expiresInSeconds)),
            tokenTypes = tokenType.toSet,
            responsesMissingTokenType = tokenType.isEmpty,
          ),
        )
      ()
