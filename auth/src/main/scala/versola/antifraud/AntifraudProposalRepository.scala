package versola.antifraud

import zio.Task

import java.time.Instant

/** The statements of the detectors. Each `detect` looks at the rollup of the last minutes, inserts a proposal for
  * what it finds that is not already proposed, decided on lately or listed, and returns how many it made, or `None`
  * when another instance was running it. Each is one bounded statement under a statement timeout, so a database
  * that is slow ends the run and does not queue behind it. */
trait AntifraudProposalRepository:
  def detectPrefixAnomalies(now: Instant, settings: AntifraudDetectors.PrefixAnomaly, run: AntifraudDetectors.Run): Task[Option[Int]]

  def detectFakeVerifies(now: Instant, settings: AntifraudDetectors.FakeVerify, run: AntifraudDetectors.Run): Task[Option[Int]]

  /** Marks open proposals created before `before` as expired, at most `limit`; returns how many. */
  def expireOpen(before: Instant, limit: Int, statementTimeout: zio.Duration): Task[Int]

  /** Deletes decided and expired proposals created before `before`, at most `limit`; returns how many. */
  def prune(before: Instant, limit: Int, statementTimeout: zio.Duration): Task[Int]
