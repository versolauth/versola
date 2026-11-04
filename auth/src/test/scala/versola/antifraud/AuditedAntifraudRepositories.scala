package versola.antifraud

import versola.audit.{AuditProbe, AuthAuditKinds}
import zio.Task

import java.time.Instant
import java.util.UUID

// The services of the antifraud admin changes, called as the specs that are about something else than the audit call them: with no
// event, which the extensions below hand down (a fresh one per call, as the controller does). A member that fits is the real call, so
// a spec of the event itself passes its own and reaches the service directly.
extension (store: AntifraudTuningStore)
  def put(tuning: Tuning, actor: String, expectedVersion: Option[Long]): Task[Long] =
    AuditProbe.event(AuthAuditKinds.AntifraudSettingsChanged).flatMap(store.put(tuning, actor, expectedVersion, _))

  def patch(patch: TuningPatch, actor: String, expectedVersion: Option[Long]): Task[StoredTuning] =
    AuditProbe.event(AuthAuditKinds.AntifraudSettingsChanged).flatMap(store.patch(patch, actor, expectedVersion, _))

  def reset(actor: String, expectedVersion: Option[Long]): Task[Long] =
    AuditProbe.event(AuthAuditKinds.AntifraudSettingsChanged).flatMap(store.reset(actor, expectedVersion, _))

extension (lists: AntifraudLists)
  def create(entry: NewListEntry, actor: String): Task[AntifraudListEntry] =
    AuditProbe.event(AuthAuditKinds.AntifraudListChanged).flatMap(lists.create(entry, actor, _))

  def delete(id: UUID, actor: String): Task[Boolean] =
    AuditProbe.event(AuthAuditKinds.AntifraudListChanged).flatMap(lists.delete(id, actor, _))

extension (counters: AntifraudCounters)
  def reset(scope: CounterScope, valueType: Option[CounterValueType], value: Option[String], actor: String): Task[Long] =
    AuditProbe.event(AuthAuditKinds.AntifraudCountersReset).flatMap(counters.reset(scope, valueType, value, actor, _))

// And the repositories themselves, for the specs of a storage that call it without an event.
extension (repository: AntifraudListRepository)
  def upsert(entry: AntifraudListEntry, actor: String, at: Instant): Task[AntifraudListEntry] =
    AuditProbe.event(AuthAuditKinds.AntifraudListChanged).flatMap(repository.upsert(entry, actor, at, _))

  def delete(id: UUID, actor: String, at: Instant): Task[Boolean] =
    AuditProbe.event(AuthAuditKinds.AntifraudListChanged).flatMap(repository.delete(id, actor, at, _))

extension (repository: AntifraudSettingsRepository)
  def put(tuning: Tuning, actor: String, at: Instant, expectedVersion: Option[Long]): Task[Option[Long]] =
    AuditProbe.event(AuthAuditKinds.AntifraudSettingsChanged).flatMap(repository.put(tuning, actor, at, expectedVersion, _))

extension (repository: AntifraudCounterResetRepository)
  def record(reset: CounterReset.Resolved, requestedBy: String, at: Instant): Task[Long] =
    AuditProbe.event(AuthAuditKinds.AntifraudCountersReset).flatMap(repository.record(reset, requestedBy, at, _))
