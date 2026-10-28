package versola.antifraud

import zio.Task

import java.time.Instant

/** The row of `af_settings`: the [[Tuning]] of the deployment. */
trait AntifraudSettingsRepository:
  /** Replaces the row and bumps the version, in one transaction. */
  def put(tuning: Tuning, actor: String, at: Instant): Task[Unit]

  /** Writes the row if there is none (a row an operator saved is left as it is) and bumps the version when it did. True when
    * it wrote. Safe to run on several instances at once. */
  def seed(tuning: Tuning, actor: String, at: Instant): Task[Boolean]

  def find: Task[Option[Tuning]]

  /** A number that changes (0 before the first change) with every change made through this repository. */
  def version: Task[Long]
