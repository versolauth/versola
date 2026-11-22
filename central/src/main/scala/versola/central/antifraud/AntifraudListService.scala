package versola.central.antifraud

import versola.central.users.AuthClient
import versola.util.{PageLimit, PageOffset}
import zio.*

import java.time.Instant
import java.util.UUID

/** The antifraud lists. Central stores them, once for the deployment, with who changed them, and every cell copies them
  * (see [[AntifraudPolicyController]]). Three things stay with the cells and are asked of them:
  *
  *  - whether an entry is valid and what its value is (a phone is hashed with a key only auth has, and what is valid belongs
  *    to the engine that enforces it): one `auth` answers;
  *  - what an entry would have matched of the last 24 hours, which each cell counts in its own event log: the sum of all;
  *  - how often an entry has matched since: the sum of what the cells counted, left out for a cell that does not answer.
  *
  * `caller` is whose scope the call is made in, from the route (see [[AntifraudCaller]], which says what a scope may do).
  *
  * ==The share of the traffic==
  * An entry that enforces something and would have matched more than [[AntifraudListService.MaxTrafficShare]] of the events
  * of the last 24 hours, over all the cells, is refused: add it as a `shadow` entry first. So an enforcing entry needs every
  * cell to answer, and a shadow one only the `auth` that validates it.
  */
trait AntifraudListService:
  def create(request: NewListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, ListEntryResponse]

  def preview(request: NewListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, PreviewResponse]

  def list(query: ListEntriesQuery, caller: AntifraudCaller): IO[AntifraudListError | Throwable, ListEntriesResponse]

  def delete(request: DeleteListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, Unit]

  def audit(entryId: Option[UUID], limit: Option[PageLimit], caller: AntifraudCaller): IO[AntifraudListError | Throwable, AuditResponse]

  /** What a cell copies: every entry that has not expired, and the version they were read at. */
  def active: Task[PolicyLists]

object AntifraudListService:

  /** An enforcing entry that would have matched more than this share of the last 24 hours of events is refused. */
  val MaxTrafficShare = 0.05

  /** The share is judged only over at least this many events. */
  val ShareMinEvents = 100L

  /** How long the audit records of changes are kept. */
  val AuditRetention: Duration = 365.days

  private val CleanUpInterval = 30.seconds
  private val PurgeBatch = 1000

  /** The service, and the fiber that removes expired entries and old audit records, which stops with the scope. */
  val live: ZLayer[AntifraudListRepository & AuthClient & Scope, Nothing, AntifraudListService] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[AntifraudListRepository]
        authClient <- ZIO.service[AuthClient]
        service = Impl(repository, authClient)
        _ <- (ZIO.sleep(CleanUpInterval) *> service.cleanUp).forever.forkScoped
      yield service

  class Impl(repository: AntifraudListRepository, authClient: AuthClient) extends AntifraudListService:
    private type Failure = AntifraudListError | Throwable

    private def check(request: NewListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError, ListEntryCheck] =
      ZIO.fromEither(caller.entryTenant(request.tenantId)).map: tenantId =>
        ListEntryCheck(
          listType = request.listType,
          valueType = request.valueType,
          value = request.value,
          phone = request.phone,
          tenantId = tenantId,
          expiresAt = request.expiresAt,
          reason = request.reason,
          // Never the client's: a request of the API is manual, whatever its body says.
          source = ListEntrySource.Manual,
          shadow = request.shadow.getOrElse(false),
          preview = false,
        )

    override def create(request: NewListEntryRequest, caller: AntifraudCaller): IO[Failure, ListEntryResponse] =
      for
        entry <- check(request, caller)
        checked <- if entry.shadow then authClient.checkAntifraudListEntry(entry) else authClient.previewAntifraudListEntry(entry)
        _ <- ZIO.foreachDiscard(checked.preview.filter(tooWide)): preview =>
          ZIO.fail(
            AntifraudListError.Invalid(
              s"it would have matched ${preview.matched.getOrElse(0L)} of ${preview.total} events of the last 24 hours: add it as a shadow entry first",
            ),
          )
        now <- Clock.instant
        stored <- repository.upsert(
          entry = ListEntryRecord(
            id = UUID.randomUUID(),
            listType = entry.listType,
            valueType = entry.valueType,
            value = checked.value,
            tenantId = entry.tenantId,
            expiresAt = entry.expiresAt,
            reason = entry.reason,
            source = entry.source,
            shadow = entry.shadow,
            createdBy = request.userId.toString,
            createdAt = now,
          ),
          actor = request.userId.toString,
          at = now,
        )
        withHits <- counted(List(stored))
      yield withHits.head

    private def tooWide(preview: PreviewResponse): Boolean =
      preview.total >= ShareMinEvents && preview.matched.exists(_.toDouble / preview.total > MaxTrafficShare)

    override def preview(request: NewListEntryRequest, caller: AntifraudCaller): IO[Failure, PreviewResponse] =
      check(request, caller).flatMap(authClient.previewAntifraudListEntry).flatMap: checked =>
        ZIO.fromOption(checked.preview).orElseFail(RuntimeException("auth answered a preview without what the entry matched"))

    override def list(query: ListEntriesQuery, caller: AntifraudCaller): IO[Failure, ListEntriesResponse] =
      for
        _ <-
          ZIO.fail(AntifraudListError.Invalid("tenantId and global exclude each other")).when(query.global.contains(true) && query.tenantId.isDefined)
        _ <- ZIO
          .fail(AntifraudListError.Forbidden("a tenant admin lists its own tenant's entries and the global ones"))
          .when(query.tenantId.exists(!caller.mayNarrowTo(_)))
        entries <- repository.list(
          filter = ListEntryFilter(
            listType = query.listType,
            valueType = query.valueType,
            tenantId = if query.global.contains(true) then Some(None) else query.tenantId.map(Some(_)),
            visibleTo = caller.visibleTo,
          ),
          limit = query.limit.getOrElse(PageLimit.Default),
          offset = query.offset.getOrElse(PageOffset.First),
        )
        withHits <- counted(entries)
      yield ListEntriesResponse(withHits)

    override def delete(request: DeleteListEntryRequest, caller: AntifraudCaller): IO[Failure, Unit] =
      for
        // Whose it is is read first: a tenant admin may not remove another tenant's entry, or a global one. A missing
        // entry is not found for every scope.
        entry <- repository.find(request.id).someOrFail(AntifraudListError.NotFound)
        _ <- ZIO.fail(AntifraudListError.Forbidden("a tenant admin removes its own tenant's entries only")).unless(caller.mayChange(entry.tenantId))
        now <- Clock.instant
        removed <- repository.delete(id = request.id, actor = request.userId.toString, at = now)
        _ <- ZIO.fail(AntifraudListError.NotFound).unless(removed)
      yield ()

    override def audit(entryId: Option[UUID], limit: Option[PageLimit], caller: AntifraudCaller): IO[Failure, AuditResponse] =
      repository.audit(entryId = entryId, limit = limit.getOrElse(PageLimit.Default), visibleTo = caller.visibleTo).map(AuditResponse(_))

    override def active: Task[PolicyLists] = Clock.instant.flatMap(repository.active)

    /** The entries with how often they matched, which the cells say. */
    private def counted(entries: List[ListEntryRecord]): Task[List[ListEntryResponse]] =
      authClient.antifraudListHits(entries.map(_.id)).map: hits =>
        val byId = hits.map(hit => hit.id -> hit).toMap
        entries.map: entry =>
          ListEntryResponse(
            id = entry.id,
            listType = entry.listType,
            valueType = entry.valueType,
            value = entry.value,
            tenantId = entry.tenantId,
            expiresAt = entry.expiresAt,
            reason = entry.reason,
            source = entry.source,
            shadow = entry.shadow,
            createdBy = entry.createdBy,
            createdAt = entry.createdAt,
            hitCount = byId.get(entry.id).fold(0L)(_.count),
            lastHitAt = byId.get(entry.id).map(_.lastAt),
          )

    /** Runs `step` in batches of [[PurgeBatch]] with a rest in between, until one comes back short. */
    private def inBatches(step: Instant => Task[Int]): UIO[Unit] =
      def loop: Task[Unit] = Clock.instant.flatMap(step).flatMap(n => if n >= PurgeBatch then ZIO.sleep(100.millis) *> loop else ZIO.unit)
      // Best effort: what is not removed now is removed by the next run, and an expired entry is not enforced meanwhile
      // (a cell honours the expiry when it looks a value up).
      loop.catchAllCause(ZIO.logWarningCause("could not clean up the antifraud lists", _))

    private[antifraud] val cleanUp: UIO[Unit] =
      inBatches(repository.purgeExpired(_, PurgeBatch)) *>
        inBatches(now => repository.pruneAudit(now.minusSeconds(AuditRetention.toSeconds), PurgeBatch))
