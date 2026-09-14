package versola.loadgen.protocol

import zio.{Clock, IO, ZIO}

/** Where §8's "each hop is a separately timed step; each flow is also timed end to end" is
  * actually implemented, for every flow class rather than once per flow class.
  *
  * Extracted from [[MobileFlows]] when [[WebFlows]] arrived: §8.4 is measured the same way
  * §8.1-8.3 are, and two copies of the measurement would be two places for a `.either` to go
  * missing -- which is what makes a failing hop report its latency before the flow gives up.
  */
private[protocol] object FlowTiming:
  def step[A](observer: FlowObserver, flow: FlowName, step: StepName)(effect: IO[ProtocolError, A]): IO[ProtocolError, A] =
    for
      start <- Clock.nanoTime
      result <- effect.either
      end <- Clock.nanoTime
      _ <- observer.step(flow, step, end - start, result.left.toOption)
      value <- ZIO.fromEither(result)
    yield value

  def flow[A](observer: FlowObserver, flow: FlowName)(effect: IO[ProtocolError, A]): IO[ProtocolError, A] =
    for
      start <- Clock.nanoTime
      result <- effect.either
      end <- Clock.nanoTime
      _ <- observer.flow(flow, end - start, result.left.toOption)
      value <- ZIO.fromEither(result)
    yield value
