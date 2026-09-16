package versola.edge.dpop

import versola.edge.{DpopAlgorithmsSyncClient, DpopPolicy, DpopPolicySyncClient}
import versola.util.{Dpop, ReloadingCache}
import zio.*
import zio.test.*

object DpopPolicyServiceSpec extends ZIOSpecDefault:

  private def algorithmsSource(values: Set[Dpop.Algorithm]) = new DpopAlgorithmsSyncClient:
    override def getAll: Task[Set[Dpop.Algorithm]] = ZIO.succeed(values)

  private def policySource(value: DpopPolicy) = new DpopPolicySyncClient:
    override def getAll: Task[DpopPolicy] = ZIO.succeed(value)

  private def env(
      initialAlgorithms: Set[Dpop.Algorithm] = Dpop.Algorithm.Default,
      initialPolicy: DpopPolicy = DpopPolicy(requireNonce = true),
      fromCentralAlgorithms: Set[Dpop.Algorithm] = Dpop.Algorithm.Default,
      fromCentralPolicy: DpopPolicy = DpopPolicy(requireNonce = true),
  ): UIO[DpopPolicyService] =
    for
      algorithmsRef <- Ref.make(initialAlgorithms)
      policyRef <- Ref.make(initialPolicy)
    yield DpopPolicyService.Impl(
      ReloadingCache(algorithmsRef),
      ReloadingCache(policyRef),
      algorithmsSource(fromCentralAlgorithms),
      policySource(fromCentralPolicy),
    )

  def spec = suite("DpopPolicyService")(
    test("answers from the cache rather than from central") {
      for
        service <- env(
          initialAlgorithms = Set(Dpop.Algorithm.ES256),
          initialPolicy = DpopPolicy(requireNonce = false),
          fromCentralAlgorithms = Set(Dpop.Algorithm.RS256),
          fromCentralPolicy = DpopPolicy(requireNonce = true),
        )
        algorithms <- service.allowedAlgorithms
        requireNonce <- service.requireNonce
      yield assertTrue(algorithms == Set(Dpop.Algorithm.ES256), !requireNonce)
    },
    // Both halves come from different endpoints, so a refresh that updated one and left the
    // other would be the kind of half-applied console change nobody looks for.
    test("refreshNow replaces both caches with what central serves") {
      for
        service <- env(
          initialAlgorithms = Set(Dpop.Algorithm.ES256),
          initialPolicy = DpopPolicy(requireNonce = true),
          fromCentralAlgorithms = Set(Dpop.Algorithm.PS256, Dpop.Algorithm.RS256),
          fromCentralPolicy = DpopPolicy(requireNonce = false),
        )
        _ <- service.refreshNow
        algorithms <- service.allowedAlgorithms
        requireNonce <- service.requireNonce
      yield assertTrue(
        algorithms == Set(Dpop.Algorithm.PS256, Dpop.Algorithm.RS256),
        !requireNonce,
      )
    },
  )
