package versola.edge.partner

import versola.edge.EdgeConfig
import versola.util.http.Observability
import zio.json.ast.Json
import zio.{Clock, Duration, IO, Task, UIO, URLayer, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.{HexFormat, UUID}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The partner's team (#549 decision 3): who may use the console and at what level, kept in the edge's own database.
  *
  * Nobody is on the team until they sign in. A person is invited by a phone number or an email address, which nothing looks up at
  * the operator; the invite waits for the first sign-in whose `/userinfo` carries that `phone_number` or `email`, and then becomes
  * the member (`partner_staff`), identified from then on by `sub`. The first administrators come from the configuration
  * (`partner.admins`) as invites of level `admin`.
  */
trait StaffService:
  /** The member this `sub` is, or, for a `sub` not on the team yet, the member an invite for the phone number or email of
    * `userInfo` makes of them. `userInfo` is asked for only when the `sub` is not a member. */
  def admit(subject: String, userInfo: IO[Throwable, Json.Obj]): IO[Throwable, StaffService.Admission]

  /** Makes the first administrators named in `partner.admins` invites of level `admin`, expiring one invite-lifetime from now. */
  def applyAdmins(identifiers: List[String]): Task[Unit]

  def list: Task[List[StaffService.Entry]]

  /** Invites `identifier` (a phone number or an email address) at `level`; renews the invite of the same identifier. */
  def invite(by: String, identifier: String, level: StaffLevel, ttl: Option[Duration]): IO[StaffService.Error | Throwable, StaffService.Entry]

  /** Changes the level of the member or invite `id` (a member's `sub`, an invite's id). */
  def setLevel(actor: String, id: String, level: StaffLevel): IO[StaffService.Error | Throwable, Unit]

  /** Removes the member or invite `id`. Not oneself, and never the last administrator. */
  def remove(actor: String, id: String): IO[StaffService.Error | Throwable, Unit]

object StaffService:
  enum Admission:
    case Member(member: StaffMember)

    /** Not on the team. `masked` is how the sign-in names itself, so the person can tell which account they used. */
    case NotAMember(masked: Option[String])

  /** One row of the Team page: a member, or an invite that waits. */
  case class Entry(
      id: String,
      subject: Option[String],
      kind: Option[IdentifierKind],
      masked: Option[String],
      display: Option[String],
      level: StaffLevel,
      member: Boolean,
      addedBy: String,
      addedAt: Instant,
      expiresAt: Option[Instant],
  )

  enum Error:
    case NotFound(what: String)
    case Invalid(reason: String)
    case Conflict(reason: String)

  /** `added_by` of an invite made from the configuration. */
  val ByConfiguration = "config"

  val live: URLayer[StaffRepository & Identifiers & EdgeConfig, StaffService] =
    ZLayer.fromFunction((repository: StaffRepository, identifiers: Identifiers, config: EdgeConfig) =>
      Impl(repository, identifiers, config.partner.inviteTtl),
    )

  class Impl(repository: StaffRepository, identifiers: Identifiers, inviteTtl: Duration) extends StaffService:

    override def admit(subject: String, userInfo: IO[Throwable, Json.Obj]): IO[Throwable, Admission] =
      repository.findMember(subject).flatMap:
        case Some(member) => ZIO.succeed(Admission.Member(member))
        case None =>
          for
            info <- userInfo
            now <- Clock.instant
            claimed = identifiers.claimedBy(info)
            matched <- firstInvite(claimed, now)
            admission <- matched match
              case Some((invite, masked)) =>
                val member = StaffMember(
                  subject = subject,
                  level = invite.level,
                  display = Identifiers.field(info, "name").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).getOrElse(masked),
                  addedBy = invite.invitedBy,
                  addedAt = now,
                )
                repository.admit(invite.id, member).flatMap: admitted =>
                  // the invite was spent a moment ago by this person's other request: the member is there, or the invite was withdrawn
                  if admitted then ZIO.succeed(Admission.Member(member))
                  else repository.findMember(subject).map(_.fold(Admission.NotAMember(Some(masked)))(Admission.Member(_)))
              case None =>
                Observability.setError("not_a_member") *>
                  ZIO.succeed(Admission.NotAMember(claimed.headOption.map(_.masked)))
          yield admission

    /** The first live invite for the identifiers the sign-in carries, the phone number before the email. */
    private def firstInvite(claimed: List[Identifiers.Claimed], now: Instant): Task[Option[(StaffInvite, String)]] =
      ZIO.foldLeft(claimed)(Option.empty[(StaffInvite, String)]): (found, identifier) =>
        if found.isDefined then ZIO.succeed(found)
        else
          repository.findInvite(identifier.kind, identifier.hash).map(_.filter(_.expiresAt.isAfter(now)).map(_ -> identifier.masked))

    override def applyAdmins(identifiers: List[String]): Task[Unit] =
      for
        now <- Clock.instant
        _ <- ZIO.foreachDiscard(identifiers): raw =>
          for
            parsed <- ZIO.fromEither(this.identifiers.parse(raw)).mapError(reason => IllegalArgumentException(s"partner.admins: $reason"))
            id <- ZIO.succeed(UUID.randomUUID())
            _ <- repository.invite(
              StaffInvite(
                id = id,
                kind = parsed.kind,
                identifierHash = parsed.hash,
                masked = parsed.masked,
                level = StaffLevel.admin,
                invitedBy = ByConfiguration,
                createdAt = now,
                expiresAt = now.plus(inviteTtl),
              ),
            )
          yield ()
      yield ()

    override def list: Task[List[Entry]] =
      for
        members <- repository.members
        invites <- repository.invites
      yield members.map(entry) ++ invites.map(entry)

    private def entry(member: StaffMember): Entry =
      Entry(
        id = member.subject,
        subject = Some(member.subject),
        kind = None,
        masked = None,
        display = Some(member.display),
        level = member.level,
        member = true,
        addedBy = member.addedBy,
        addedAt = member.addedAt,
        expiresAt = None,
      )

    private def entry(invite: StaffInvite): Entry =
      Entry(
        id = invite.id.toString,
        subject = None,
        kind = Some(invite.kind),
        masked = Some(invite.masked),
        display = None,
        level = invite.level,
        member = false,
        addedBy = invite.invitedBy,
        addedAt = invite.createdAt,
        expiresAt = Some(invite.expiresAt),
      )

    override def invite(by: String, identifier: String, level: StaffLevel, ttl: Option[Duration]): IO[Error | Throwable, Entry] =
      for
        parsed <- ZIO.fromEither(identifiers.parse(identifier)).mapError(Error.Invalid(_))
        now <- Clock.instant
        invite = StaffInvite(
          id = UUID.randomUUID(),
          kind = parsed.kind,
          identifierHash = parsed.hash,
          masked = parsed.masked,
          level = level,
          invitedBy = by,
          createdAt = now,
          expiresAt = now.plus(ttl.getOrElse(inviteTtl)),
        )
        _ <- repository.invite(invite)
        // `invite` keeps the id of an invite there already
        stored <- repository.findInvite(parsed.kind, parsed.hash).someOrFail(IllegalStateException("the invite was written and cannot be read"))
      yield entry(stored)

    override def setLevel(actor: String, id: String, level: StaffLevel): IO[Error | Throwable, Unit] =
      repository.findMember(id).flatMap:
        case Some(member) =>
          for
            others <- otherAdmins(id)
            _ <- ZIO.fail(Error.Conflict("this would leave the team without an administrator"))
              .when(member.level == StaffLevel.admin && level != StaffLevel.admin && others == 0)
            _ <- repository.setMemberLevel(id, level)
          yield ()
        case None =>
          inviteId(id).flatMap(invite => repository.setInviteLevel(invite, level)).flatMap(found =>
            ZIO.fail(Error.NotFound(s"member or invite '$id'")).unless(found).unit,
          )

    override def remove(actor: String, id: String): IO[Error | Throwable, Unit] =
      repository.findMember(id).flatMap:
        case Some(member) =>
          for
            _ <- ZIO.fail(Error.Conflict("you cannot remove yourself from the team")).when(id == actor)
            others <- otherAdmins(id)
            _ <- ZIO.fail(Error.Conflict("this would leave the team without an administrator")).when(member.level == StaffLevel.admin && others == 0)
            _ <- repository.removeMember(id)
          yield ()
        case None =>
          inviteId(id).flatMap(repository.removeInvite).flatMap(found => ZIO.fail(Error.NotFound(s"member or invite '$id'")).unless(found).unit)

    private def otherAdmins(subject: String): Task[Int] =
      repository.members.map(_.count(member => member.level == StaffLevel.admin && member.subject != subject))

    private def inviteId(id: String): IO[Error, UUID] =
      ZIO.attempt(UUID.fromString(id)).orElseFail(Error.NotFound(s"member or invite '$id'"))

/** The identifiers of people, as the team knows them: what a phone number or an email address is, what the Team page shows of it,
  * and the keyed hash that is all the database holds. The key is the edge's own (derived from its `token-encryption` key, so every
  * replica that shares the configuration shares it), which is what stops a copy of `partner_staff_invites` from being tested
  * against a list of phone numbers.
  */
trait Identifiers:
  /** `raw` as an identifier: what it is, in its normal form. */
  def parse(raw: String): Either[String, Identifiers.Parsed]

  /** The identifiers the claims of a `/userinfo` carry, phone number first. An identifier the operator says is not verified
    * (`phone_number_verified` / `email_verified` false) is not one: an invite must not be taken by someone who typed an address
    * they cannot read. */
  def claimedBy(userInfo: Json.Obj): List[Identifiers.Claimed]

object Identifiers:
  case class Parsed(kind: IdentifierKind, normal: String, hash: String, masked: String)
  case class Claimed(kind: IdentifierKind, hash: String, masked: String)

  val live: URLayer[EdgeConfig, Identifiers] =
    ZLayer.fromFunction((config: EdgeConfig) => Impl(config.security.tokenEncryption.key))

  /** The member `name` of a JSON object, if it has one. */
  def field(json: Json.Obj, name: String): Option[Json] = json.fields.collectFirst { case (`name`, value) => value }

  private val Label = "versola-partner-staff-invite-v1"
  private val Phone = "^\\+[1-9][0-9]{6,14}$".r
  private val Email = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r

  class Impl(keyMaterial: Array[Byte]) extends Identifiers:
    private val key: SecretKeySpec =
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(keyMaterial, "HmacSHA256"))
      SecretKeySpec(mac.doFinal(Label.getBytes(StandardCharsets.UTF_8)), "HmacSHA256")

    private def hash(kind: IdentifierKind, normal: String): String =
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(key)
      HexFormat.of().formatHex(mac.doFinal(s"$kind:$normal".getBytes(StandardCharsets.UTF_8)))

    override def parse(raw: String): Either[String, Parsed] =
      val trimmed = raw.trim
      if trimmed.contains('@') then
        val normal = trimmed.toLowerCase
        Either.cond(Email.matches(normal), parsed(IdentifierKind.email, normal), "not an email address")
      else
        val normal = trimmed.filterNot(c => c == ' ' || c == '-' || c == '(' || c == ')')
        Either.cond(Phone.matches(normal), parsed(IdentifierKind.phone, normal), "not a phone number: international format, +7701...")

    private def parsed(kind: IdentifierKind, normal: String): Parsed =
      Parsed(kind, normal, hash(kind, normal), mask(kind, normal))

    override def claimedBy(userInfo: Json.Obj): List[Claimed] =
      def claim(name: String, verifiedName: String, kind: IdentifierKind): Option[Claimed] =
        for
          value <- Identifiers.field(userInfo, name).flatMap(_.asString)
          // an explicit `false` is a refusal; a claim the operator does not state either way is the operator's own account identifier
          if !Identifiers.field(userInfo, verifiedName).flatMap(_.asBoolean).exists(verified => !verified)
          parsed <- parse(value).toOption.filter(_.kind == kind)
        yield Claimed(kind, parsed.hash, parsed.masked)
      List(claim("phone_number", "phone_number_verified", IdentifierKind.phone), claim("email", "email_verified", IdentifierKind.email)).flatten

    /** What shows of an identifier on a page: enough for a person to recognise their own, not enough to read a stranger's. */
    private def mask(kind: IdentifierKind, normal: String): String =
      kind match
        case IdentifierKind.phone => s"${normal.take(2)}${"•" * (normal.length - 4).max(1)}${normal.takeRight(2)}"
        case IdentifierKind.email =>
          val at = normal.indexOf('@')
          s"${normal.take(1)}•••${normal.drop(at)}"
