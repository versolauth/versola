package versola.oauth

import versola.oauth.authorize.model.AuthorizeResponse
import versola.oauth.conversation.model.ConversationRecord
import zio.UIO
import zio.metrics.{Metric, MetricLabel}

/** Business-level authentication metrics.
  *
  * These metrics deliberately do not contain `client_id` or any user identifier. Use the
  * request logs, keyed by `auth.id`, when a distinct-user view is required.
  */
object AuthMetrics:
  private val authorizeTotal = Metric.counter("auth_authorize_total")
  private val conversationStartedTotal = Metric.counter("auth_conversation_started_total")
  private val conversationCompletedTotal = Metric.counter("auth_conversation_completed_total")
  private val conversationStepTotal = Metric.counter("auth_conversation_step_total")

  def authorizeOutcome(response: AuthorizeResponse): UIO[Unit] =
    response match
      case _: AuthorizeResponse.Authorized => authorize("silent")
      case _: AuthorizeResponse.Initialize => authorize("interactive")

  def authorizeError(reason: String): UIO[Unit] =
    authorize("error", Some(reason))

  def conversationStarted(record: ConversationRecord): UIO[Unit] =
    conversationStartedTotal.tagged(label("kind", conversationKind(record))).increment

  def conversationCompleted(record: ConversationRecord): UIO[Unit] =
    conversationCompletedTotal.tagged(label("kind", conversationKind(record))).increment

  def stepPassed(step: String): UIO[Unit] =
    recordStep(step, "passed")

  def stepFailed(step: String): UIO[Unit] =
    recordStep(step, "failed")

  /** The kind is inferred from persisted conversation state so completion uses the same label as
    * start without adding another field to the conversation record. A requested ACR is the
    * durable marker for a step-up; other existing-session conversations are reauthentication.
    */
  def conversationKind(record: ConversationRecord): String =
    if record.priorSessionId.isEmpty then "login"
    else if record.targetAcr.isDefined then "step_up"
    else "reauth"

  private def authorize(outcome: String, reason: Option[String] = None): UIO[Unit] =
    authorizeTotal.tagged(
      label("outcome", outcome) ++ label("reason", reason.getOrElse("none")),
    ).increment

  private def recordStep(stepName: String, result: String): UIO[Unit] =
    conversationStepTotal.tagged(
      label("step", stepName) ++ label("result", result),
    ).increment

  private def label(name: String, value: String): Set[MetricLabel] =
    Set(MetricLabel(name, value))