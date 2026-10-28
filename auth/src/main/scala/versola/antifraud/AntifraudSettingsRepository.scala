package versola.antifraud

import zio.Task

import java.time.Instant

/** The row of `af_settings`: the [[Tuning]] of the deployment. */
trait AntifraudSettingsRepository:
  /** Replaces the row and bumps the version, in one transaction. */
  def put(tuning: Tuning, actor: String, at: Instant): Task[Unit]

  def find: Task[Option[Tuning]]

  /** A number that changes with every change made through this repository. */
  def version: Task[Long]
