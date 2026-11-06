package versola.util.audit

/** What one service says about its own audit events: which kinds it emits and how serious each is. The kinds and the severities of
  * each service are its own; the core takes the catalog the service gives it and applies it to every
  * event, so that the severity is the same wherever the kind is recorded and the kinds in the metrics are a closed set.
  *
  * A service makes one as an `object` next to its kinds, and a spec of the service asserts that every kind it emits is
  * [[isKnown]]. */
trait AuditCatalog:
  /** Whether `kind` is one this service emits: what the `kind` label of the metrics is limited to (any other is counted as `other`),
    * so a kind built from a request can never make the label unbounded. */
  def isKnown(kind: AuditKind): Boolean

  /** A kind that is `high` whatever the outcome: an attack, or a change of the keys of the system. */
  def isHigh(kind: AuditKind): Boolean = false

  /** A kind that is at least `notice` when it succeeds too: what changes how an account is reached, a secret that was looked at. */
  def isNotice(kind: AuditKind): Boolean = false

  /** `high` for a high kind; else `notice` for every failure and denial and for a notice kind; else `info`. Fixed here and not at the
    * call site, so that a kind is rated the same wherever it is recorded. */
  final def severity(kind: AuditKind, outcome: AuditOutcome): AuditSeverity =
    if isHigh(kind) then AuditSeverity.high
    else if outcome != AuditOutcome.success || isNotice(kind) then AuditSeverity.notice
    else AuditSeverity.info

object AuditCatalog:
  /** A catalog of a fixed list of kinds, with the high and the notice ones named. For a service whose kinds are all listed; one
    * that also builds kinds from parts (`<area>.<resource>.<operation>`) extends [[AuditCatalog]] itself. */
  def of(kinds: Set[AuditKind], high: Set[AuditKind] = Set.empty, notice: Set[AuditKind] = Set.empty): AuditCatalog =
    new AuditCatalog:
      override def isKnown(kind: AuditKind): Boolean = kinds.contains(kind)

      override def isHigh(kind: AuditKind): Boolean = high.contains(kind)

      override def isNotice(kind: AuditKind): Boolean = notice.contains(kind)
