package versola.loadgen.metrics

import zio.json.*
import zio.test.*
import zio.ZIO

/** What the SUT said about its own tokens, which is the only one of the three access-token TTLs
  * in this codebase that describes the system under test rather than the emulator's belief about
  * it. A set per client, so the merge has to be a union in both directions and has to keep the
  * clients apart -- collapsing them is what would hide the divergence the header exists to show.
  */
object TokenObservationsSpec extends ZIOSpecDefault:

  private def observations(clientId: String, ttl: Long, tokenType: Option[String]): TokenObservations =
    TokenObservations(Map(clientId -> Set(ttl)), tokenType.toSet, tokenType.isEmpty)

  def spec = suite("TokenObservations")(
    test("keeps every distinct TTL a client was issued, per client") {
      val merged = TokenObservations.mergeAll(
        List(
          observations("mobile-otp", 900L, Some("Bearer")),
          observations("mobile-otp", 300L, Some("Bearer")),
          observations("web-otp", 900L, Some("Bearer")),
        ),
      )
      assertTrue(
        merged.accessTokenTtlsByClient == Map("mobile-otp" -> Set(900L, 300L), "web-otp" -> Set(900L)),
        merged.tokenTypes == Set("Bearer"),
      )
    },
    // The merge runs over drivers and over a restarted driver's carried totals, so it sees the
    // same fact repeatedly. Unlike the counters beside it in `CarriedTotals`, seeing it twice
    // must not make it two facts.
    test("merging the same observation twice says the same thing as merging it once") {
      val one = observations("mobile-otp", 900L, Some("Bearer"))
      assertTrue(one.merge(one) == one, TokenObservations.empty.merge(one) == one)
    },
    test("a response with no token_type is recorded as the RFC 6749 §5.1 violation it is") {
      val merged = observations("mobile-otp", 900L, None).merge(observations("web-otp", 900L, Some("Bearer")))
      assertTrue(
        merged.responsesMissingTokenType,
        merged.tokenTypes == Set("Bearer"),
      )
    },
    test("round-trips through JSON, since it travels on every driver report") {
      val merged = observations("mobile-otp", 900L, Some("Bearer")).merge(observations("mobile-otp", 300L, Some("Bearer")))
      assertTrue(merged.toJson.fromJson[TokenObservations] == Right(merged))
    },
    suite("TokenObserver")(
      // Process-global and shared with every other spec in this run, so this asserts what it
      // recorded rather than the whole snapshot -- an equality against `empty` here would depend
      // on which specs ran first.
      test("accumulates what the token endpoint answered") {
        for
          _ <- ZIO.succeed(TokenObserver.record("spec-client", 900L, Some("Bearer")))
          _ <- ZIO.succeed(TokenObserver.record("spec-client", 300L, Some("Bearer")))
          _ <- ZIO.succeed(TokenObserver.record("spec-client", 900L, Some("Bearer")))
          observed <- TokenObserver.current
        yield assertTrue(
          observed.accessTokenTtlsByClient.get("spec-client") == Some(Set(900L, 300L)),
          observed.tokenTypes.contains("Bearer"),
        )
      },
    ),
  )
