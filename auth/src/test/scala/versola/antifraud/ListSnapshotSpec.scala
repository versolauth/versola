package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.UnitSpecBase
import zio.test.*

import java.time.Instant
import java.util.UUID

object ListSnapshotSpec extends UnitSpecBase:

  private val now = Instant.parse("2026-10-23T10:00:00Z")
  private val tenant = TenantId("tenant-a")

  private def ctx(
      tenantId: Option[TenantId] = Some(tenant),
      subjectHash: Option[String] = None,
      destPrefix: Option[String] = None,
      deviceId: Option[String] = None,
      ip: Option[String] = None,
      asn: Option[Int] = None,
      userAgent: Option[String] = None,
      ja4: Option[String] = None,
      signals: Map[String, String] = Map.empty,
  ) = AntifraudContext(
    tenantId = tenantId,
    clientId = ClientId("c"),
    action = AntifraudAction.otp_request,
    subjectHash = subjectHash,
    destPrefix = destPrefix,
    deviceId = deviceId,
    ip = ip,
    asn = asn,
    userAgent = userAgent,
    ja4 = ja4,
    signals = signals,
  )

  private def entry(
      listType: AntifraudListType,
      valueType: AntifraudValueType,
      value: String,
      tenantId: Option[TenantId] = None,
      expiresAt: Option[Instant] = None,
  ) = ListedValue(UUID.randomUUID(), listType, valueType, value, tenantId, expiresAt)

  private def snapshot(entries: ListedValue*) = ListSnapshot.build(entries)

  def spec = suite("ListSnapshot")(
    suite("matching")(
      test("finds an exact destination, device, JA4 fingerprint and user agent") {
        val ua = "Mozilla/5.0 (test)"
        val s = snapshot(
          entry(AntifraudListType.deny, AntifraudValueType.phone_hash, "hash-1"),
          entry(AntifraudListType.deny, AntifraudValueType.device_id, "dev-1"),
          entry(AntifraudListType.deny, AntifraudValueType.ja4_hash, "t13d1516h2_abc"),
          entry(AntifraudListType.deny, AntifraudValueType.ua_hash, ListSnapshot.sha256Hex(ua)),
        )
        assertTrue(
          s.lookup(ctx(subjectHash = Some("hash-1")), now).decisive.isDefined,
          s.lookup(ctx(deviceId = Some("dev-1")), now).decisive.isDefined,
          s.lookup(ctx(ja4 = Some("t13d1516h2_abc")), now).decisive.isDefined,
          s.lookup(ctx(userAgent = Some(ua)), now).decisive.isDefined,
          s.lookup(ctx(subjectHash = Some("hash-2"), deviceId = Some("dev-2"), userAgent = Some("other")), now).decisive.isEmpty,
        )
      },
      test("a number prefix matches the destination's prefix at every length up to its own") {
        val s = snapshot(entry(AntifraudListType.deny, AntifraudValueType.prefix, "4915"))
        assertTrue(
          s.lookup(ctx(destPrefix = Some("491512")), now).decisive.isDefined,
          s.lookup(ctx(destPrefix = Some("4915")), now).decisive.isDefined,
          s.lookup(ctx(destPrefix = Some("491")), now).decisive.isEmpty,
          s.lookup(ctx(destPrefix = Some("491612")), now).decisive.isEmpty,
        )
      },
      test("networks match by prefix length, in both families, and an exact ip is not a network") {
        val s = snapshot(
          entry(AntifraudListType.deny, AntifraudValueType.cidr, "10.1.0.0/16"),
          entry(AntifraudListType.deny, AntifraudValueType.cidr, "2001:db8::/32"),
          entry(AntifraudListType.deny, AntifraudValueType.ip, "192.0.2.7"),
          entry(AntifraudListType.deny, AntifraudValueType.cidr, "0.0.0.0/0"),
        )
        def hit(ip: String) = s.lookup(ctx(ip = Some(ip)), now).decisive.map(_.value)
        assertTrue(
          hit("10.1.200.3").contains("10.1.0.0/16"),
          hit("2001:db8:ffff::1").contains("2001:db8::/32"),
          hit("192.0.2.7").contains("192.0.2.7"),
          // only /0 contains it
          hit("192.0.2.8").contains("0.0.0.0/0"),
          // an IPv4-mapped address is the IPv4 address
          hit("::ffff:10.1.0.9").contains("10.1.0.0/16"),
          // the families do not mix: the /0 of IPv4 does not contain an IPv6 address
          hit("2002::1").isEmpty,
          hit("not-an-ip").isEmpty,
          hit("example.com").isEmpty,
        )
      },
      test("matches an autonomous system, and a mail domain from the signals") {
        val s = snapshot(
          entry(AntifraudListType.watch, AntifraudValueType.asn, "64500"),
          entry(AntifraudListType.deny, AntifraudValueType.email_domain, "mailinator.com"),
        )
        assertTrue(
          s.lookup(ctx(asn = Some(64500)), now).watch.isDefined,
          s.lookup(ctx(asn = Some(64501)), now).watch.isEmpty,
          s.lookup(ctx(signals = Map("email_domain" -> " Mailinator.com ")), now).decisive.isDefined,
        )
      },
      test("an asn above Int.MaxValue is matched as unsigned") {
        val s = snapshot(entry(AntifraudListType.deny, AntifraudValueType.asn, "4200000000"))
        assertTrue(s.lookup(ctx(asn = Some(4200000000L.toInt)), now).decisive.isDefined)
      },
    ),
    suite("scope and expiry")(
      test("a tenant's entry applies to that tenant only, a global one to all, also without a tenant") {
        val s = snapshot(
          entry(AntifraudListType.deny, AntifraudValueType.device_id, "mine", tenantId = Some(tenant)),
          entry(AntifraudListType.deny, AntifraudValueType.device_id, "all"),
        )
        assertTrue(
          s.lookup(ctx(deviceId = Some("mine")), now).decisive.isDefined,
          s.lookup(ctx(tenantId = Some(TenantId("tenant-b")), deviceId = Some("mine")), now).decisive.isEmpty,
          s.lookup(ctx(tenantId = None, deviceId = Some("mine")), now).decisive.isEmpty,
          s.lookup(ctx(tenantId = None, deviceId = Some("all")), now).decisive.isDefined,
        )
      },
      test("an entry is honoured until the instant it expires, on the lookup and not only on refresh") {
        val s = snapshot(entry(AntifraudListType.deny, AntifraudValueType.device_id, "d", expiresAt = Some(now.plusSeconds(60))))
        assertTrue(
          s.lookup(ctx(deviceId = Some("d")), now).decisive.isDefined,
          s.lookup(ctx(deviceId = Some("d")), now.plusSeconds(59)).decisive.isDefined,
          s.lookup(ctx(deviceId = Some("d")), now.plusSeconds(60)).decisive.isEmpty,
        )
      },
      test("an expired entry does not shadow a live one of the same value") {
        val s = snapshot(
          entry(AntifraudListType.deny, AntifraudValueType.device_id, "d", expiresAt = Some(now.minusSeconds(1))),
          entry(AntifraudListType.allow, AntifraudValueType.device_id, "d"),
        )
        assertTrue(s.lookup(ctx(deviceId = Some("d")), now).decisive.map(_.listType).contains(AntifraudListType.allow))
      },
    ),
    suite("precedence")(
      test("on a tie deny beats fake beats allow") {
        def winner(types: AntifraudListType*) =
          snapshot(types.map(entry(_, AntifraudValueType.device_id, "d"))*).lookup(ctx(deviceId = Some("d")), now).decisive.map(_.listType)
        assertTrue(
          winner(AntifraudListType.allow, AntifraudListType.deny).contains(AntifraudListType.deny),
          winner(AntifraudListType.deny, AntifraudListType.allow).contains(AntifraudListType.deny),
          winner(AntifraudListType.allow, AntifraudListType.fake).contains(AntifraudListType.fake),
          winner(AntifraudListType.fake, AntifraudListType.deny).contains(AntifraudListType.deny),
        )
      },
      test("the more specific value decides: an allowed address inside a denied network is allowed") {
        val s = snapshot(
          entry(AntifraudListType.deny, AntifraudValueType.cidr, "203.0.113.0/24"),
          entry(AntifraudListType.allow, AntifraudValueType.ip, "203.0.113.9"),
        )
        assertTrue(
          s.lookup(ctx(ip = Some("203.0.113.9")), now).decisive.map(_.listType).contains(AntifraudListType.allow),
          s.lookup(ctx(ip = Some("203.0.113.10")), now).decisive.map(_.listType).contains(AntifraudListType.deny),
        )
      },
      test("a denied address inside an allowed network is denied; a longer network beats a shorter one") {
        val s = snapshot(
          entry(AntifraudListType.allow, AntifraudValueType.cidr, "203.0.113.0/24"),
          entry(AntifraudListType.deny, AntifraudValueType.ip, "203.0.113.9"),
          entry(AntifraudListType.deny, AntifraudValueType.cidr, "198.51.0.0/16"),
          entry(AntifraudListType.allow, AntifraudValueType.cidr, "198.51.100.0/24"),
        )
        def winner(ip: String) = s.lookup(ctx(ip = Some(ip)), now).decisive.map(_.listType)
        assertTrue(
          winner("203.0.113.9").contains(AntifraudListType.deny),
          winner("203.0.113.1").contains(AntifraudListType.allow),
          winner("198.51.100.1").contains(AntifraudListType.allow),
          winner("198.51.1.1").contains(AntifraudListType.deny),
        )
      },
      test("an exact destination outranks its number prefix; a prefix outranks an autonomous system") {
        val s = snapshot(
          entry(AntifraudListType.deny, AntifraudValueType.prefix, "4915"),
          entry(AntifraudListType.allow, AntifraudValueType.phone_hash, "vip"),
          entry(AntifraudListType.allow, AntifraudValueType.asn, "64500"),
        )
        assertTrue(
          s.lookup(ctx(subjectHash = Some("vip"), destPrefix = Some("491512")), now).decisive.map(_.listType).contains(AntifraudListType.allow),
          s.lookup(ctx(destPrefix = Some("491512"), asn = Some(64500)), now).decisive.map(_.listType).contains(AntifraudListType.deny),
        )
      },
      test("watch is reported alongside the decision, never competing with it") {
        val s = snapshot(
          entry(AntifraudListType.watch, AntifraudValueType.device_id, "d"),
          entry(AntifraudListType.allow, AntifraudValueType.prefix, "49"),
        )
        val m = s.lookup(ctx(deviceId = Some("d"), destPrefix = Some("4915")), now)
        assertTrue(m.watch.isDefined, m.decisive.map(_.listType).contains(AntifraudListType.allow))
      },
    ),
    suite("values")(
      test("normalises what is stored so that it is what a lookup compares") {
        assertTrue(
          ListValues.normalize(AntifraudValueType.cidr, "10.1.77.9/16") == Right("10.1.0.0/16"),
          ListValues.normalize(AntifraudValueType.cidr, "2001:DB8::1/32") == Right("2001:db8:0:0:0:0:0:0/32"),
          ListValues.normalize(AntifraudValueType.ip, "::ffff:1.2.3.4") == Right("1.2.3.4"),
          ListValues.normalize(AntifraudValueType.asn, "as64500") == Right("64500"),
          ListValues.normalize(AntifraudValueType.email_domain, "Example.COM") == Right("example.com"),
          ListValues.normalize(AntifraudValueType.ua_hash, ListSnapshot.sha256Hex("x").toUpperCase) == Right(ListSnapshot.sha256Hex("x")),
        )
      },
      test("refuses what cannot be compared") {
        val bad = List(
          AntifraudValueType.cidr -> "10.0.0.0/33",
          AntifraudValueType.cidr -> "10.0.0.0",
          AntifraudValueType.cidr -> "host.example/24",
          AntifraudValueType.ip -> "10.0.0",
          AntifraudValueType.prefix -> "+4915",
          AntifraudValueType.prefix -> "49151234567",
          AntifraudValueType.asn -> "4294967296",
          AntifraudValueType.ua_hash -> "Mozilla",
          AntifraudValueType.email_domain -> "a@b.com",
          AntifraudValueType.device_id -> "  ",
        )
        assertTrue(bad.forall((t, v) => ListValues.normalize(t, v).isLeft))
      },
      test("an entry with a value invalid for its type is left out and counted, not fatal") {
        val s = snapshot(
          entry(AntifraudListType.deny, AntifraudValueType.cidr, "garbage"),
          entry(AntifraudListType.deny, AntifraudValueType.device_id, "ok"),
        )
        assertTrue(s.size == 1, s.skipped == 1, s.lookup(ctx(deviceId = Some("ok")), now).decisive.isDefined)
      },
    ),
    suite("speed")(
      test("a lookup over 100k entries of every kind is far below the 0.1 ms the request can spend") {
        val entries = (0 until 100000).map: i =>
          i % 5 match
            case 0 => entry(AntifraudListType.deny, AntifraudValueType.phone_hash, s"hash-$i")
            case 1 => entry(AntifraudListType.watch, AntifraudValueType.device_id, s"dev-$i")
            case 2 => entry(AntifraudListType.deny, AntifraudValueType.cidr, s"${10 + i % 200}.${(i / 200) % 256}.${i % 256}.0/${16 + i % 9}")
            case 3 => entry(AntifraudListType.fake, AntifraudValueType.prefix, (100000 + i % 900000).toString.take(1 + i % 6))
            case _ => entry(AntifraudListType.allow, AntifraudValueType.ip, s"172.${16 + i % 16}.${(i / 16) % 256}.${i % 255}")
        val s = snapshot(entries*)
        val contexts = (0 until 1000).map: i =>
          ctx(
            subjectHash = Some(s"hash-${i * 5}"), deviceId = Some(s"dev-${i * 5 + 1}"), destPrefix = Some("491512"),
            ip = Some(s"10.${i % 256}.1.2"), userAgent = Some("Mozilla/5.0 (X11; Linux x86_64)"), asn = Some(i),
          )
        // Warm the JIT, then time many lookups; the bound is generous so that a slow CI machine passes it, and a
        // lookup that scanned the entries (100k of them) would exceed it by orders of magnitude.
        (0 until 3).foreach(_ => contexts.foreach(c => s.lookup(c, now)))
        val rounds = 20
        val start = System.nanoTime()
        var found = 0
        (0 until rounds).foreach(_ => contexts.foreach(c => if s.lookup(c, now).decisive.isDefined then found += 1))
        val perLookupMicros = (System.nanoTime() - start) / 1000.0 / (rounds * contexts.size)
        assertTrue(s.size == 100000, found > 0, perLookupMicros < 100.0)
      },
    ),
  )
