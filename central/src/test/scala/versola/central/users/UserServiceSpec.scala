package versola.central.users

import versola.central.configuration.clients.ApplicationType
import versola.central.configuration.clients.{AuthFlow, AuthMethod, ClientApproval, ClientId, OAuthClientRecord, OAuthClientService}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.topology.{CellId, CellPlacement, CellRecord, CellState, RegionId, TopologyRepository}
import versola.util.{Email, Patch, Phone, RedirectUri, Secret, SecureRandom, UnitSpecBase}
import zio.*
import zio.durationInt
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

object UserServiceSpec extends UnitSpecBase:

  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val tenantId = TenantId("t1")
  private val email = Email("user@example.com")
  private val indexRecord = UserDirectoryRecord(userId, cellId = None)
  private val key = DirectoryKey.derive(Secret(Array.fill(16)(7.toByte)))
  private val cell = CellId("kz-1")
  private def held(email: Option[Email] = None, phone: Option[Phone] = None, login: Option[Login] = None) =
    AuthClient.UserDto(email = email, phone = phone, login = login, claims = Json.Obj())

  private def cellRecord(id: CellId) =
    CellRecord(
      id = id,
      regionId = RegionId("almaty"),
      internalUrl = s"http://$id:9003",
      mtlsUrl = None,
      trustedCertificates = Nil,
      state = CellState.Open,
      weight = 100,
      publicKeys = Nil,
    )
  private val registeredCells = Vector("kz-1", "kz-2", "kz-3").map(CellId(_))
  private def weighted(id: String, weight: Int, state: CellState = CellState.Open) =
    cellRecord(CellId(id)).copy(weight = weight, state = state)
  private def macs(count: Int, seed: Long): Vector[IdentifierMac] =
    val random = new java.util.Random(seed)
    Vector.fill(count)(IdentifierMac(Array.fill(32)(random.nextInt().toByte)))

  class Env:
    val userRepository = stub[UserRepository]
    val authClient = stub[AuthClient]
    val oAuthClientService = stub[OAuthClientService]
    val secureRandom = stub[SecureRandom]
    val topology = stub[TopologyRepository]
    val moves = stub[UserMoveRepository]
    val service = UserService.Impl(userRepository, authClient, oAuthClientService, secureRandom, key, topology, moves)
    Unsafe.unsafe(u ?=> Runtime.default.unsafe.run(moves.find.succeedsWith(None))(using summon, u).getOrThrow())

  def spec = suite("UserService")(
    test("findById returns enriched record when user found") {
      val env = Env()
      for
        _ <- env.userRepository.find.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUser.succeedsWith(Some(held(email = Some(email))))
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector.empty)
        result <- env.service.findById(userId)
      yield assertTrue(result.map(_.id).contains(userId))
    },
    test(
      "findById names the user's cell and its move; a user the cell does not answer for, because it is leaving, is shown without what the cell holds",
    ) {
      val env = Env()
      val move = UserMove(
        userId = userId,
        fromCell = CellId("kz-1"),
        toCell = CellId("kz-2"),
        phase = MovePhase.Leaving,
        startedAt = Instant.parse("2027-02-01T10:00:00Z"),
        updatedAt = Instant.parse("2027-02-01T10:00:00Z"),
        attempts = 0,
        error = None,
        reason = MoveReason.Admin,
        notBefore = Instant.parse("2027-02-01T10:00:00Z"),
        departedAt = None,
        moveId = UUID.fromString("00000000-0000-7000-8000-0000000000a1"),
        digest = None,
      )
      for
        _ <- env.userRepository.find.succeedsWith(Some(UserDirectoryRecord(userId, Some(CellId("kz-1")))))
        _ <- env.authClient.getUser.failsWith(AuthClient.UserLeavingCell)
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector.empty)
        _ <- env.moves.find.succeedsWith(Some(move))
        result <- env.service.findById(userId)
      yield assertTrue(
        result.flatMap(_.cell).contains(CellId("kz-1")),
        result.flatMap(_.move).map(_.phase).contains(MovePhase.Leaving),
        result.flatMap(_.move).map(_.to).contains(CellId("kz-2")),
        result.exists(_.email.isEmpty),
      )
    },
    test("findById returns None when user not found") {
      val env = Env()
      for
        _ <- env.userRepository.find.succeedsWith(None)
        result <- env.service.findById(userId)
      yield assertTrue(result.isEmpty)
    },
    test("create inserts user and returns generated id") {
      val env = Env()
      val newId = UUID.fromString("00000000-0000-0000-0000-000000000002")
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(newId)
        _ <- env.topology.getCells.succeedsWith(Vector.empty)
        _ <- env.userRepository.create.succeedsWith(())
        result <- env.service.create(CreateUserRequest(Some(email), None, None))
      yield assertTrue(
        result == UserId(newId),
        // With no cell registered the user is in the auth central is configured with.
        env.userRepository.create.calls.map(_._1) == List(UserDirectoryRecord(UserId(newId), cellId = None)),
      )
    },
    // versolauth/versola#547 Phase 2 step 3
    test("create places the user in an open cell, never in one that takes no users") {
      val env = Env()
      val newId = UUID.fromString("00000000-0000-0000-0000-000000000002")
      def cellOf(id: String, state: CellState, weight: Int) =
        CellRecord(
          id = CellId(id),
          regionId = RegionId("almaty"),
          internalUrl = s"http://$id:9003",
          mtlsUrl = None,
          trustedCertificates = Nil,
          state = state,
          weight = weight,
          publicKeys = Nil,
        )
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(newId)
        _ <- env.topology.getCells.succeedsWith(
          Vector(
            cellOf("kz-1", CellState.Closed, 100),
            cellOf("kz-2", CellState.Open, 100),
            cellOf("kz-3", CellState.Draining, 100),
            cellOf("kz-4", CellState.Open, 0),
          ),
        )
        _ <- env.userRepository.create.succeedsWith(())
        _ <- ZIO.foreachDiscard(1 to 20)(_ => env.service.create(CreateUserRequest(Some(email), None, None)))
      yield assertTrue(env.userRepository.create.calls.map(_._1.cellId).distinct == List(Some(CellId("kz-2"))))
    },
    test("the weights of the open cells decide where a user is placed, and no cell that takes users is none") {
      def cellOf(id: String, weight: Int) =
        CellRecord(
          id = CellId(id),
          regionId = RegionId("almaty"),
          internalUrl = s"http://$id:9003",
          mtlsUrl = None,
          trustedCertificates = Nil,
          state = CellState.Open,
          weight = weight,
          publicKeys = Nil,
        )
      val cells = Vector(cellOf("kz-1", 30), cellOf("kz-2", 70))
      assertTrue(
        List(0.0, 0.29, 0.31, 0.99).map(CellPlacement.pick(cells, _)) == List("kz-1", "kz-1", "kz-2", "kz-2").map(id => Some(CellId(id))),
        CellPlacement.pick(Vector.empty, 0.5).isEmpty,
        CellPlacement.pick(Vector(cellOf("kz-1", 0)), 0.5).isEmpty,
      )
    },
    // versolauth/versola#1041
    test("indexRegistered claims the MAC of the credential for a new id in the caller's cell") {
      val env = Env()
      val minted = UUID.fromString("00000000-0000-0000-0000-000000000009")
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(minted)
        _ <- env.userRepository.claim.returnsZIO((_, candidate) => ZIO.succeed(candidate))
        result <-
          env.service.indexRegistered(RegisteredUserRequest(email = None, phone = Some(Phone("+15551234567")), login = None, cell = Some(cell)))
        calls = env.userRepository.claim.calls
      yield assertTrue(
        result == RegisteredUserResponse(userId = UserId(minted), cell = Some(cell)),
        calls.map(_._2) == List(UserDirectoryRecord(UserId(minted), Some(cell))),
        calls.map(_._1.map(_.kind)) == List(List(IdentifierKind.Phone)),
        // Only the MAC leaves the service, and it is the one a search for the same phone computes.
        calls.flatMap(_._1.map(_.mac.toList)) == List(key.phone(Phone("+15551234567")).mac.toList),
      )
    },
    test("indexRegistered answers the owner and the owner's cell for a credential claimed before, whatever cell asks") {
      val env = Env()
      val owner = UserDirectoryRecord(userId, Some(CellId("kz-2")))
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(UUID.fromString("00000000-0000-0000-0000-000000000009"))
        _ <- env.userRepository.claim.succeedsWith(owner)
        result <- env.service.indexRegistered(RegisteredUserRequest(email = Some(email), phone = None, login = None, cell = Some(cell)))
      yield assertTrue(result == RegisteredUserResponse(userId = userId, cell = Some(CellId("kz-2"))))
    },
    test("the MAC of an identifier depends on its kind and on the key, and is the same for the same value") {
      val other = DirectoryKey.derive(Secret(Array.fill(16)(8.toByte)))
      val phone = Phone("+15551234567")
      assertTrue(
        key.phone(phone).mac.toList == key.phone(phone).mac.toList,
        key.phone(phone).mac.toList != other.phone(phone).mac.toList,
        key.phone(phone).mac.toList != key.login(Login("+15551234567")).mac.toList,
        key.phone(phone).mac.length == 32,
      )
    },
    test("getRoles delegates to authClient") {
      val env = Env()
      for
        _ <- env.authClient.getUserRoles.succeedsWith(List(RoleId("admin")))
        result <- env.service.getRoles(userId, tenantId)
      yield assertTrue(result == List(RoleId("admin")))
    },
    test("getSessions enriches client entries with expiresAt computed from client access token ttl") {
      val env = Env()
      val clientId = ClientId("web-app")
      val enteredAt = Instant.parse("2024-01-01T00:00:00Z")
      val ttl = 5.minutes
      val client = OAuthClientRecord(
        id = clientId,
        tenantId = tenantId,
        clientName = Map("en" -> "Web App"),
        redirectUris = Set(RedirectUri("https://example.com/callback")),
        scope = Set.empty,
        secret = None,
        previousSecret = None,
        accessTokenTtl = ttl,
        refreshTokenTtl = 7776000.seconds,
        permissions = Set.empty,
        theme = "",
        authFlow = Some(AuthFlow.default),
        otpTemplateId = "default",
        frontChannelLogoutUri = None,
        frontChannelLogoutSessionRequired = false,
        backChannelLogoutUri = None,
        logoUri = None,
        policyUri = None,
        tosUri = None,
        consentFlow = None,
        dpopBoundAccessTokens = false,
        dpopSigningAlgs = Set.empty,
        dpopMinRsaKeySize = None,
        authMethod = AuthMethod.client_secret,
        mtlsAuth = None,
        certificateBoundAccessTokens = false,
        jwks = None,
        requireSignedRequestObject = false,
        requirePushedAuthorizationRequests = false,
        edgeSigningKey = None,
        edgeClientCertificate = None,
        template = None,
        createdAt = Instant.EPOCH,
        themeOverrides = None,
        applicationType = ApplicationType.web,
        partnerId = None,
        approval = ClientApproval.Approved,
      )
      val sessionDto = AuthClient.SessionDto(
        publicId = "public-session-1",
        clients = List(AuthClient.ClientEntryDto(clientId, enteredAt)),
        platform = Some("desktop"),
        os = None,
        browser = None,
        version = None,
        createdAt = enteredAt.toString,
        expiresAt = enteredAt.plusSeconds(3600),
        idleExpiresAt = None,
        userAgent = None,
      )
      for
        _ <- env.authClient.getUserSessions.succeedsWith(List(sessionDto))
        _ <- env.oAuthClientService.getAllClients.succeedsWith(Vector(client))
        result <- env.service.getSessions(userId)
      yield assertTrue(
        result.size == 1,
        result.head.clients.head.clientId == clientId,
        result.head.clients.head.expiresAt == enteredAt.plus(ttl.asJava),
      )
    },
    test("getSessions passes the session's expiry, idle expiry and user agent through") {
      val env = Env()
      val created = Instant.parse("2024-01-01T00:00:00Z")
      val agent = AuthClient.UserAgentDto(
        id = "00000000-0000-0000-0000-0000000000a1",
        userAgent = Some("raw"),
        platform = Some("desktop"),
        os = Some("macOS 14.6"),
        browser = Some("Chrome"),
        version = Some("130"),
        createdAt = created,
        expiresAt = created.plusSeconds(86400),
      )
      val sessionDto = AuthClient.SessionDto(
        publicId = "public-session-1",
        clients = Nil,
        platform = Some("desktop"),
        os = Some("macOS 14.6"),
        browser = Some("Chrome"),
        version = Some("130"),
        createdAt = created.toString,
        expiresAt = created.plusSeconds(3600),
        idleExpiresAt = Some(created.plusSeconds(600)),
        userAgent = Some(agent),
      )
      for
        _ <- env.authClient.getUserSessions.succeedsWith(List(sessionDto))
        _ <- env.oAuthClientService.getAllClients.succeedsWith(Vector.empty)
        result <- env.service.getSessions(userId)
      yield assertTrue(
        result.map(_.expiresAt) == List(created.plusSeconds(3600)),
        result.map(_.idleExpiresAt) == List(Some(created.plusSeconds(600))),
        result.flatMap(_.userAgent).map(_.id) == List(agent.id),
        result.flatMap(_.userAgent).flatMap(_.userAgent) == List("raw"),
        result.flatMap(_.userAgent).map(_.expiresAt) == List(created.plusSeconds(86400)),
      )
    },
    test("getDevices maps a signed-in device with its session and a signed-out one with no session") {
      val env = Env()
      val created = Instant.parse("2024-01-01T00:00:00Z")
      def agent(id: String, raw: Option[String]) = AuthClient.UserAgentDto(
        id = id,
        userAgent = raw,
        platform = Some("ios"),
        os = Some("iOS 17"),
        browser = Some("Safari"),
        version = Some("17"),
        createdAt = created,
        expiresAt = created.plusSeconds(86400),
      )
      val signedIn = AuthClient.DeviceDto(
        userAgent = Some(agent("ua-1", Some("raw-1"))),
        session = Some(
          AuthClient.SessionDto(
            publicId = "public-session-1",
            clients = Nil,
            platform = Some("ios"),
            os = Some("iOS 17"),
            browser = Some("Safari"),
            version = Some("17"),
            createdAt = created.toString,
            expiresAt = created.plusSeconds(3600),
            idleExpiresAt = None,
            userAgent = Some(agent("ua-1", Some("raw-1"))),
          ),
        ),
        endedSessionIds = Nil,
      )
      val signedOut = AuthClient.DeviceDto(userAgent = Some(agent("ua-2", None)), session = None, endedSessionIds = List("old-1", "old-2"))
      for
        _ <- env.authClient.getUserDevices.succeedsWith(List(signedIn, signedOut))
        _ <- env.oAuthClientService.getAllClients.succeedsWith(Vector.empty)
        result <- env.service.getDevices(userId)
      yield assertTrue(
        env.authClient.getUserDevices.calls == List(userId),
        result.map(_.userAgent.map(_.id)) == List(Some("ua-1"), Some("ua-2")),
        result.map(_.session.map(_.publicId)) == List(Some("public-session-1"), None),
        result.head.session.map(_.expiresAt).contains(created.plusSeconds(3600)),
        result.head.session.flatMap(_.idleExpiresAt).isEmpty,
        result.head.userAgent.flatMap(_.userAgent).contains("raw-1"),
        result(1).userAgent.flatMap(_.userAgent).isEmpty,
        result(1).userAgent.map(_.expiresAt).contains(created.plusSeconds(86400)),
        result(1).endedSessionIds == List("old-1", "old-2"),
      )
    },
    test("forgetUserAgent delegates to authClient and answers whether it was forgotten") {
      val env = Env()
      for
        _ <- env.authClient.forgetUserAgent.succeedsWith(false)
        forgotten <- env.service.forgetUserAgent(userId, "ua-1")
      yield assertTrue(!forgotten, env.authClient.forgetUserAgent.calls == List((userId, "ua-1")))
    },
    test("getSessions sorts client entries by enteredAt descending (most recently issued first)") {
      val env = Env()
      val clientIdA = ClientId("web-app-a")
      val clientIdB = ClientId("web-app-b")
      val ttl = 5.minutes
      val olderEnteredAt = Instant.parse("2024-01-01T00:00:00Z")
      val newerEnteredAt = Instant.parse("2024-01-01T01:00:00Z")

      def client(id: ClientId) = OAuthClientRecord(
        id = id,
        tenantId = tenantId,
        clientName = Map("en" -> "Web App"),
        redirectUris = Set(RedirectUri("https://example.com/callback")),
        scope = Set.empty,
        secret = None,
        previousSecret = None,
        accessTokenTtl = ttl,
        refreshTokenTtl = 7776000.seconds,
        permissions = Set.empty,
        theme = "",
        authFlow = Some(AuthFlow.default),
        otpTemplateId = "default",
        frontChannelLogoutUri = None,
        frontChannelLogoutSessionRequired = false,
        backChannelLogoutUri = None,
        logoUri = None,
        policyUri = None,
        tosUri = None,
        consentFlow = None,
        dpopBoundAccessTokens = false,
        dpopSigningAlgs = Set.empty,
        dpopMinRsaKeySize = None,
        authMethod = AuthMethod.client_secret,
        mtlsAuth = None,
        certificateBoundAccessTokens = false,
        jwks = None,
        requireSignedRequestObject = false,
        requirePushedAuthorizationRequests = false,
        edgeSigningKey = None,
        edgeClientCertificate = None,
        template = None,
        createdAt = Instant.EPOCH,
        themeOverrides = None,
        applicationType = ApplicationType.web,
        partnerId = None,
        approval = ClientApproval.Approved,
      )
      val sessionDto = AuthClient.SessionDto(
        publicId = "public-session-1",
        clients = List(
          AuthClient.ClientEntryDto(clientIdA, olderEnteredAt),
          AuthClient.ClientEntryDto(clientIdB, newerEnteredAt),
        ),
        platform = Some("desktop"),
        os = None,
        browser = None,
        version = None,
        createdAt = olderEnteredAt.toString,
        expiresAt = olderEnteredAt.plusSeconds(3600),
        idleExpiresAt = None,
        userAgent = None,
      )
      for
        _ <- env.authClient.getUserSessions.succeedsWith(List(sessionDto))
        _ <- env.oAuthClientService.getAllClients.succeedsWith(Vector(client(clientIdA), client(clientIdB)))
        result <- env.service.getSessions(userId)
      yield assertTrue(
        result.head.clients.map(_.clientId) == List(clientIdB, clientIdA),
      )
    },
    test("findByEmail returns enriched record when user found") {
      val env = Env()
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUser.succeedsWith(Some(held(email = Some(email))))
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector.empty)
        result <- env.service.findByEmail(email)
      yield assertTrue(result.map(_.id).contains(userId))
    },
    test("findByPhone returns enriched record when user found") {
      val env = Env()
      val phone = Phone("+16502530000")
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUser.succeedsWith(Some(held(email = Some(email))))
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector.empty)
        result <- env.service.findByPhone(phone)
      yield assertTrue(
        result.map(_.id).contains(userId),
        // The directory is asked by the MAC, and the identifiers shown are what auth holds.
        env.userRepository.findByIdentifier.calls.map(i => (i.kind, i.mac.toList)) == List((IdentifierKind.Phone, key.phone(phone).mac.toList)),
        result.flatMap(_.email).contains(email),
      )
    },
    test("findByLogin returns enriched record when user found") {
      val env = Env()
      val login = Login("jdoe")
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUser.succeedsWith(Some(held(email = Some(email))))
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector.empty)
        result <- env.service.findByLogin(login)
      yield assertTrue(result.map(_.id).contains(userId))
    },
    test("findById defaults to an empty claims object when auth has none for the user") {
      val env = Env()
      for
        _ <- env.userRepository.find.succeedsWith(Some(indexRecord))
        _ <- env.authClient.getUser.succeedsWith(None)
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector.empty)
        result <- env.service.findById(userId)
      yield assertTrue(result.map(_.claims).contains(Json.Obj()))
    },
    test("invalidateSession delegates to authClient") {
      val env = Env()
      for
        _ <- env.authClient.invalidateSession.succeedsWith(())
        _ <- env.service.invalidateSession(userId)
      yield assertTrue(env.authClient.invalidateSession.calls == List(userId))
    },
    test("endSession delegates to authClient and answers whether the session was ended") {
      val env = Env()
      for
        _ <- env.authClient.endSession.succeedsWith(false)
        ended <- env.service.endSession(userId, "c2lk")
      yield assertTrue(!ended, env.authClient.endSession.calls == List((userId, "c2lk")))
    },
    test("supersedeSession passes the user and the session to authClient and answers that the session was ended") {
      val env = Env()
      for
        _ <- env.authClient.supersedeSession.succeedsWith(true)
        ended <- env.service.supersedeSession(SupersededSessionRequest(userId = userId, sessionId = "c2lk"))
      yield assertTrue(ended, env.authClient.supersedeSession.calls == List((userId, "c2lk")), env.authClient.endSession.calls.isEmpty)
    },
    test("supersedeSession answers false when authClient does not know the session as the user's") {
      val env = Env()
      for
        _ <- env.authClient.supersedeSession.succeedsWith(false)
        ended <- env.service.supersedeSession(SupersededSessionRequest(userId = userId, sessionId = "c2lk"))
      yield assertTrue(!ended, env.authClient.supersedeSession.calls == List((userId, "c2lk")))
    },
    test("revokeRefreshTokens delegates to authClient") {
      val env = Env()
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        _ <- env.authClient.revokeRefreshTokens.succeedsWith(())
        _ <- env.service.revokeRefreshTokens(userId, ClientId("app"))
      yield assertTrue(env.authClient.revokeRefreshTokens.calls == List((userId, ClientId("app"))))
    },
    test("create fails with UserConflict when repository reports a conflict") {
      val env = Env()
      val newId = UUID.fromString("00000000-0000-0000-0000-000000000003")
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(newId)
        _ <- env.topology.getCells.succeedsWith(Vector.empty)
        _ <- env.userRepository.create.failsWith(UserConflict)
        result <- env.service.create(CreateUserRequest(Some(email), None, None)).either
      yield assertTrue(result == Left(UserConflict))
    },
    // versolauth/versola#1046
    test("lookup answers the user and the cell that hold a credential, by its MAC and without claiming it") {
      val env = Env()
      val phone = Phone("+15551234567")
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(Some(UserDirectoryRecord(userId, Some(cell))))
        found <- env.service.lookup(LookupUserRequest(email = None, phone = Some(phone), login = None))
      yield assertTrue(
        found == Some(RegisteredUserResponse(userId = userId, cell = Some(cell))),
        env.userRepository.findByIdentifier.calls.map(i => (i.kind, i.mac.toList)) == List((IdentifierKind.Phone, key.phone(phone).mac.toList)),
        env.userRepository.claim.calls.isEmpty,
      )
    },
    test("lookup answers nobody for a credential nobody holds") {
      val env = Env()
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(None)
        found <- env.service.lookup(LookupUserRequest(email = Some(email), phone = None, login = None))
      yield assertTrue(found.isEmpty)
    },
    // versolauth/versola#1048
    test("homeCell answers the cell of the user that holds a login, by its MAC") {
      val env = Env()
      val login = Login("jdoe")
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(Some(UserDirectoryRecord(userId, Some(cell))))
        result <- env.service.homeCell(HomeCellRequest(login = Some(login), userId = None, email = None, phone = None))
      yield assertTrue(
        result == Some(cell),
        env.userRepository.findByIdentifier.calls.map(i => (i.kind, i.mac.toList)) == List((IdentifierKind.Login, key.login(login).mac.toList)),
        env.topology.getCells.calls.isEmpty,
      )
    },
    test("homeCell answers nothing for a login held by a user with no cell, and does not pick a decoy") {
      val env = Env()
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(Some(UserDirectoryRecord(userId, None)))
        result <- env.service.homeCell(HomeCellRequest(login = Some(Login("jdoe")), userId = None, email = None, phone = None))
      yield assertTrue(result.isEmpty, env.topology.getCells.calls.isEmpty)
    },
    test("homeCell answers a registered cell for a login nobody holds, the same one every time, picked by the MAC of the login") {
      val env = Env()
      val login = Login("nobody")
      val request = HomeCellRequest(login = Some(login), userId = None, email = None, phone = None)
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(None)
        _ <- env.topology.getCells.succeedsWith(registeredCells.map(cellRecord))
        first <- env.service.homeCell(request)
        second <- env.service.homeCell(request)
      yield assertTrue(
        first.exists(registeredCells.contains),
        second == first,
        first == UserService.decoyCell(registeredCells, key.login(login).mac),
      )
    },
    test("homeCell answers nothing for a login nobody holds when no cell is registered") {
      val env = Env()
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(None)
        _ <- env.topology.getCells.succeedsWith(Vector.empty)
        result <- env.service.homeCell(HomeCellRequest(login = Some(Login("nobody")), userId = None, email = None, phone = None))
      yield assertTrue(result.isEmpty)
    },
    test("homeCell answers the cell of a known user id") {
      val env = Env()
      for
        _ <- env.userRepository.find.succeedsWith(Some(UserDirectoryRecord(userId, Some(cell))))
        result <- env.service.homeCell(HomeCellRequest(login = None, userId = Some(userId), email = None, phone = None))
      yield assertTrue(result == Some(cell), env.userRepository.find.calls == List(userId), env.topology.getCells.calls.isEmpty)
    },
    test("homeCell answers nothing for an unknown user id, not a decoy") {
      val env = Env()
      for
        _ <- env.userRepository.find.succeedsWith(None)
        result <- env.service.homeCell(HomeCellRequest(login = None, userId = Some(userId), email = None, phone = None))
      yield assertTrue(result.isEmpty, env.topology.getCells.calls.isEmpty)
    },
    test("homeCell answers nothing for a request with both a login and a user id, and for one with neither") {
      val env = Env()
      for
        both <- env.service.homeCell(HomeCellRequest(login = Some(Login("jdoe")), userId = Some(userId), email = None, phone = None))
        neither <- env.service.homeCell(HomeCellRequest(login = None, userId = None, email = None, phone = None))
      yield assertTrue(
        both.isEmpty,
        neither.isEmpty,
        env.userRepository.findByIdentifier.calls.isEmpty,
        env.userRepository.find.calls.isEmpty,
        env.topology.getCells.calls.isEmpty,
      )
    },
    // versolauth/versola#1052
    test("homeCell for an email answers the cell of the user that holds it, by its MAC, and does not read the cells") {
      val env = Env()
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(Some(UserDirectoryRecord(userId, Some(cell))))
        result <- env.service.homeCell(HomeCellRequest(login = None, userId = None, email = Some(email), phone = None))
      yield assertTrue(
        result == Some(cell),
        env.userRepository.findByIdentifier.calls.map(i => (i.kind, i.mac.toList)) == List((IdentifierKind.Email, key.email(email).mac.toList)),
        env.topology.getCells.calls.isEmpty,
      )
    },
    test("homeCell for a phone answers the cell of the user that holds it, by its MAC") {
      val env = Env()
      val phone = Phone("+15551234567")
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(Some(UserDirectoryRecord(userId, Some(cell))))
        result <- env.service.homeCell(HomeCellRequest(login = None, userId = None, email = None, phone = Some(phone)))
      yield assertTrue(
        result == Some(cell),
        env.userRepository.findByIdentifier.calls.map(i => (i.kind, i.mac.toList)) == List((IdentifierKind.Phone, key.phone(phone).mac.toList)),
        env.topology.getCells.calls.isEmpty,
      )
    },
    test("homeCell for an address held by a user with no cell answers nothing, and does not place it") {
      val env = Env()
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(Some(UserDirectoryRecord(userId, None)))
        result <- env.service.homeCell(HomeCellRequest(login = None, userId = None, email = Some(email), phone = None))
      yield assertTrue(result.isEmpty, env.topology.getCells.calls.isEmpty)
    },
    test("homeCell for an address nobody holds answers the cell placementCell picks among the cells that take users, the same every time") {
      val env = Env()
      val phone = Phone("+15551234567")
      val cells = Vector(weighted("kz-1", 100), weighted("kz-2", 300), weighted("kz-3", 600), weighted("kz-4", 500, CellState.Closed))
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(None)
        _ <- env.topology.getCells.succeedsWith(cells)
        byEmail <- env.service.homeCell(HomeCellRequest(login = None, userId = None, email = Some(email), phone = None))
        again <- env.service.homeCell(HomeCellRequest(login = None, userId = None, email = Some(email), phone = None))
        byPhone <- env.service.homeCell(HomeCellRequest(login = None, userId = None, email = None, phone = Some(phone)))
      yield assertTrue(
        byEmail == UserService.placementCell(cells, key.email(email).mac),
        again == byEmail,
        byPhone == UserService.placementCell(cells, key.phone(phone).mac),
        byEmail.exists(Set("kz-1", "kz-2", "kz-3").map(CellId(_)).contains),
        byPhone.exists(Set("kz-1", "kz-2", "kz-3").map(CellId(_)).contains),
      )
    },
    test("homeCell for an address nobody holds answers nothing when no cell takes users: none, closed, draining, weightless") {
      val env = Env()
      val request = HomeCellRequest(login = None, userId = None, email = Some(email), phone = None)
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(None)
        _ <- env.topology.getCells.succeedsWith(Vector.empty)
        none <- env.service.homeCell(request)
        _ <- env.topology.getCells.succeedsWith(
          Vector(weighted("kz-1", 100, CellState.Closed), weighted("kz-2", 100, CellState.Draining), weighted("kz-3", 0)),
        )
        nonTaking <- env.service.homeCell(request)
      yield assertTrue(none.isEmpty, nonTaking.isEmpty)
    },
    test("homeCell for a login nobody holds is still the decoy over every cell, taking or not") {
      val env = Env()
      val login = Login("nobody")
      val cells = Vector(weighted("kz-1", 100, CellState.Closed), weighted("kz-2", 0), weighted("kz-3", 100, CellState.Draining))
      for
        _ <- env.userRepository.findByIdentifier.succeedsWith(None)
        _ <- env.topology.getCells.succeedsWith(cells)
        result <- env.service.homeCell(HomeCellRequest(login = Some(login), userId = None, email = None, phone = None))
      yield assertTrue(result == UserService.decoyCell(cells.map(_.id), key.login(login).mac), result.isDefined)
    },
    test("homeCell answers nothing for a request with two fields or with none, and asks nobody") {
      val env = Env()
      val phone = Phone("+15551234567")
      val requests = List(
        HomeCellRequest(login = Some(Login("jdoe")), userId = None, email = Some(email), phone = None),
        HomeCellRequest(login = None, userId = None, email = Some(email), phone = Some(phone)),
        HomeCellRequest(login = None, userId = Some(userId), email = None, phone = Some(phone)),
        HomeCellRequest(login = None, userId = Some(userId), email = Some(email), phone = None),
        HomeCellRequest(login = Some(Login("jdoe")), userId = Some(userId), email = Some(email), phone = Some(phone)),
        HomeCellRequest(login = None, userId = None, email = None, phone = None),
      )
      for answers <- ZIO.foreach(requests)(env.service.homeCell)
      yield assertTrue(
        answers.forall(_.isEmpty),
        env.userRepository.findByIdentifier.calls.isEmpty,
        env.userRepository.find.calls.isEmpty,
        env.topology.getCells.calls.isEmpty,
      )
    },
    test("placementCell answers nothing for no cell, and never a cell that is closed, draining or weightless") {
      val cells = Vector(
        weighted("a-closed", 1000, CellState.Closed),
        weighted("b-draining", 1000, CellState.Draining),
        weighted("c-zero", 0),
        weighted("d-open", 1),
      )
      val all = macs(500, seed = 11)
      assertTrue(
        UserService.placementCell(Vector.empty, all.head).isEmpty,
        all.forall(mac => UserService.placementCell(cells, mac) == Some(CellId("d-open"))),
        all.forall(mac => UserService.placementCell(cells.init, mac).isEmpty),
      )
    },
    test("placementCell is the same on every call and whatever order the cells come in") {
      val cells = Vector(weighted("kz-1", 100), weighted("kz-2", 300), weighted("kz-3", 600), weighted("kz-4", 300))
      val all = macs(300, seed = 3)
      assertTrue(
        all.forall: mac =>
          val expected = UserService.placementCell(cells, mac)
          expected.isDefined &&
          expected == UserService.placementCell(cells, mac) &&
          expected == UserService.placementCell(cells.reverse, mac) &&
          expected == UserService.placementCell(cells.drop(2) ++ cells.take(2), mac),
      )
    },
    test("placementCell puts addresses on the cells in proportion to their weights: 10%, 30%, 60% over 20 000 MACs within 2 points") {
      val cells = Vector(weighted("kz-1", 100), weighted("kz-2", 300), weighted("kz-3", 600))
      val n = 20000
      val counts = macs(n, seed = 20260101L).flatMap(UserService.placementCell(cells, _)).groupBy(identity).view.mapValues(_.size).toMap
      val share = cells.map(c => c.id -> counts.getOrElse(c.id, 0).toDouble / n).toMap
      assertTrue(
        counts.values.sum == n,
        math.abs(share(CellId("kz-1")) - 0.10) <= 0.02,
        math.abs(share(CellId("kz-2")) - 0.30) <= 0.02,
        math.abs(share(CellId("kz-3")) - 0.60) <= 0.02,
      )
    },
    test("placementCell: a cell's weight changing moves addresses only to it (heavier) or from it (lighter)") {
      val before = Vector(weighted("kz-1", 100), weighted("kz-2", 300), weighted("kz-3", 600))
      val all = macs(5000, seed = 17)
      def moves(after: Vector[CellRecord]) =
        all.flatMap(mac =>
          (UserService.placementCell(before, mac), UserService.placementCell(after, mac)) match
            case (Some(from), Some(to)) if from != to => Some(from -> to)
            case _ => None,
        )
      val heavier = moves(Vector(weighted("kz-1", 100), weighted("kz-2", 500), weighted("kz-3", 600)))
      val lighter = moves(Vector(weighted("kz-1", 100), weighted("kz-2", 100), weighted("kz-3", 600)))
      assertTrue(
        heavier.nonEmpty,
        heavier.forall { case (from, to) => to == CellId("kz-2") && from != CellId("kz-2") },
        lighter.nonEmpty,
        lighter.forall { case (from, to) => from == CellId("kz-2") && to != CellId("kz-2") },
      )
    },
    test("placementCell: a cell closing or being removed moves only its own addresses") {
      val cells = Vector(weighted("kz-1", 100), weighted("kz-2", 300), weighted("kz-3", 600))
      val all = macs(5000, seed = 23)
      val removed = cells.filterNot(_.id == CellId("kz-2"))
      val closed = cells.map(c => if c.id == CellId("kz-2") then c.copy(state = CellState.Closed) else c)
      def check(after: Vector[CellRecord]) = all.forall: mac =>
        val was = UserService.placementCell(cells, mac)
        val now = UserService.placementCell(after, mac)
        if was == Some(CellId("kz-2")) then now.exists(_ != CellId("kz-2")) else now == was
      assertTrue(
        all.exists(mac => UserService.placementCell(cells, mac) == Some(CellId("kz-2"))),
        check(removed),
        check(closed),
      )
    },
    test("the degenerate digests, all zero bits and all one bits, give a number inside (0, 1) and a finite positive score") {
      val zeros = UserService.unitOf(Array.fill(32)(0.toByte))
      val ones = UserService.unitOf(Array.fill(32)(0xff.toByte))
      val scores = List(zeros, ones).flatMap(u => List(1, 100, Int.MaxValue).map(UserService.rendezvousScore(_, u)))
      assertTrue(
        zeros > 0.0 && zeros < 1.0,
        ones > 0.0 && ones < 1.0,
        math.log(zeros).isFinite && math.log(zeros) < 0.0,
        math.log(ones).isFinite && math.log(ones) < 0.0,
        scores.forall(score => score.isFinite && score > 0.0),
      )
    },
    test("placementCell does not throw for a MAC of zeros or of ones") {
      val cells = Vector(weighted("kz-1", 100), weighted("kz-2", 300))
      assertTrue(
        UserService.placementCell(cells, IdentifierMac(Array.fill(32)(0.toByte))).isDefined,
        UserService.placementCell(cells, IdentifierMac(Array.fill(32)(0xff.toByte))).isDefined,
      )
    },
    test("decoyCell answers nothing for no cell and the only cell for one") {
      val mac = IdentifierMac(Array.fill(32)(5.toByte))
      assertTrue(
        UserService.decoyCell(Vector.empty, mac).isEmpty,
        UserService.decoyCell(Vector(cell), mac) == Some(cell),
      )
    },
    test("decoyCell does not depend on the order the cells are given in") {
      val random = new java.util.Random(42)
      val macs = Vector.fill(50)(IdentifierMac(Array.fill(32)(random.nextInt().toByte)))
      assertTrue(
        macs.forall(mac =>
          UserService.decoyCell(registeredCells, mac) == UserService.decoyCell(registeredCells.reverse, mac) &&
            UserService.decoyCell(registeredCells, mac) == UserService.decoyCell(registeredCells.drop(1) ++ registeredCells.take(1), mac),
        ),
      )
    },
    test("decoyCell spreads logins over every cell") {
      val cells = Vector("kz-1", "kz-2", "kz-3", "kz-4").map(CellId(_))
      val random = new java.util.Random(7)
      val chosen = Vector.fill(200)(IdentifierMac(Array.fill(32)(random.nextInt().toByte))).flatMap(UserService.decoyCell(cells, _))
      assertTrue(chosen.toSet == cells.toSet)
    },
    test("indexRegistered fails with UserIndexConflict when credentials resolve to multiple users") {
      val env = Env()
      val request = RegisteredUserRequest(email = Some(email), phone = None, login = None, cell = None)
      for
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(UUID.fromString("00000000-0000-0000-0000-000000000009"))
        _ <- env.userRepository.claim.failsWith(UserIndexConflict)
        result <- env.service.indexRegistered(request).either
      yield assertTrue(result == Left(UserIndexConflict))
    },
    test("a user is read back as it was just written: what is queued for auth is applied over what auth holds") {
      val env = Env()
      val version = UUID.fromString("00000000-0000-0000-0000-000000000007")
      val phone = Phone("+15551234567")
      for
        _ <- env.userRepository.find.succeedsWith(Some(indexRecord))
        // auth has not been given the user yet
        _ <- env.authClient.getUser.succeedsWith(None)
        _ <- env.userRepository.pendingEvents.succeedsWith(
          Vector(
            OutboxEvent.UpsertUser(userId = userId, version = version, email = Some(email), phone = None, login = Some(Login("jdoe"))),
            OutboxEvent.PatchUser(userId = userId, version = version, email = None, phone = Some(Patch.Modified(phone)), login = Some(Patch.Deleted)),
          ),
        )
        created <- env.service.findById(userId)
        // auth has the user, and a deletion is queued
        _ <- env.authClient.getUser.succeedsWith(Some(held(email = Some(email))))
        _ <- env.userRepository.pendingEvents.succeedsWith(Vector(OutboxEvent.DeleteUser(userId)))
        deleted <- env.service.findById(userId)
      yield assertTrue(
        created.flatMap(_.email).contains(email),
        created.flatMap(_.phone).contains(phone),
        created.exists(_.login.isEmpty),
        deleted.exists(u => u.email.isEmpty && u.phone.isEmpty),
      )
    },
    test("patch replaces the MAC of what changed, removes what was deleted, and tells auth only what it named") {
      val env = Env()
      val version = UUID.fromString("00000000-0000-0000-0000-000000000007")
      val request = PatchUserRequest(userId, Some(Patch.Modified(email)), Some(Patch.Deleted), None)
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(version)
        _ <- env.userRepository.replaceIdentifiers.succeedsWith(())
        _ <- env.service.patch(request)
        calls = env.userRepository.replaceIdentifiers.calls
      yield assertTrue(
        calls.map(_._1) == List(userId),
        calls.map(_._2.map(i => (i.kind, i.mac.toList))) == List(List((IdentifierKind.Email, key.email(email).mac.toList))),
        calls.map(_._3) == List(Set(IdentifierKind.Phone)),
        calls.map(_._4) == List(OutboxEvent.PatchUser(
          userId = userId,
          version = version,
          email = Some(Patch.Modified(email)),
          phone = Some(Patch.Deleted),
          login = None,
        )),
      )
    },
    test("patch fails with UserConflict when the identifier belongs to another user") {
      val env = Env()
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(UUID.fromString("00000000-0000-0000-0000-000000000007"))
        _ <- env.userRepository.replaceIdentifiers.failsWith(UserConflict)
        result <- env.service.patch(PatchUserRequest(userId, Some(Patch.Modified(email)), None, None)).either
      yield assertTrue(result == Left(UserConflict))
    },
    test("patchClaims delegates to authClient") {
      val env = Env()
      val patch = Json.Obj("test" -> Json.Bool(true))
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        _ <- env.authClient.patchUserClaims.succeedsWith(())
        _ <- env.service.patchClaims(userId, patch)
      yield assertTrue(env.authClient.patchUserClaims.calls == List((userId, patch)))
    },
    test("delete purges the user in auth before removing the index row") {
      val env = Env()
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        order <- Ref.make(List.empty[String])
        _ <- env.userRepository.find.succeedsWith(Some(indexRecord))
        _ <- env.authClient.deleteUser.returnsZIO(_ => order.update(_ :+ "auth"))
        _ <- env.userRepository.delete.returnsZIO(_ => order.update(_ :+ "index"))
        _ <- env.service.delete(userId)
        seen <- order.get
      yield assertTrue(
        seen == List("auth", "index"),
        env.authClient.deleteUser.calls == List(userId),
        env.userRepository.delete.calls == List(userId),
      )
    },
    test("delete fails with UserNotFound for an id that is not indexed and touches nothing") {
      val env = Env()
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        _ <- env.userRepository.find.succeedsWith(None)
        exit <- env.service.delete(userId).exit
      yield assertTrue(
        exit == Exit.fail(UserNotFound),
        env.authClient.deleteUser.calls.isEmpty,
        env.userRepository.delete.calls.isEmpty,
      )
    },
    test("delete leaves the index row in place when auth's purge fails") {
      val env = Env()
      val failure = RuntimeException("auth unavailable")
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        _ <- env.userRepository.find.succeedsWith(Some(indexRecord))
        _ <- env.authClient.deleteUser.failsWith(failure)
        exit <- env.service.delete(userId).exit
      yield assertTrue(
        exit == Exit.fail(failure),
        env.userRepository.delete.calls.isEmpty,
      ).label("a user auth still knows must stay findable, or the deletion could never be retried")
    },
    test("updateRoles enqueues a role update via repository") {
      val env = Env()
      val request = UpdateUserRolesRequest(userId, tenantId, Set(RoleId("admin")), Set(RoleId("viewer")))
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        _ <- env.userRepository.enqueueRoleUpdate.succeedsWith(())
        _ <- env.service.updateRoles(request)
      yield assertTrue(
        env.userRepository.enqueueRoleUpdate.calls ==
          List((userId, tenantId, Set(RoleId("admin")), Set(RoleId("viewer")))),
      )
    },
    test("resetLimits delegates request fields to authClient") {
      val env = Env()
      val request = ResetUserLimitsRequest(userId, tenantId, Some(email), None)
      for
        _ <- env.authClient.resetUserLimits.succeedsWith(())
        _ <- env.service.resetLimits(request)
      yield assertTrue(
        env.authClient.resetUserLimits.calls == List((userId, tenantId, Some(email), None)),
      )
    },
    test("listPasskeys delegates to authClient") {
      val env = Env()
      val passkey = PasskeyInfo(
        id = "cred-1",
        name = Some("Phone"),
        deviceType = "MultiDevice",
        transports = List("internal"),
        backedUp = true,
        backupEligible = true,
        lastUsedAt = None,
        createdAt = "2024-01-01T00:00:00Z",
      )
      for
        _ <- env.authClient.listPasskeys.succeedsWith(List(passkey))
        result <- env.service.listPasskeys(userId)
      yield assertTrue(result == List(passkey))
    },
    test("renamePasskey delegates request fields to authClient") {
      val env = Env()
      val request = RenamePasskeyRequest(userId, "cred-1", Some("New name"))
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        _ <- env.authClient.renamePasskey.succeedsWith(())
        _ <- env.service.renamePasskey(request)
      yield assertTrue(env.authClient.renamePasskey.calls == List((userId, "cred-1", Some("New name"))))
    },
    test("deletePasskey delegates to authClient") {
      val env = Env()
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        _ <- env.authClient.deletePasskey.succeedsWith(())
        _ <- env.service.deletePasskey(userId, "cred-1")
      yield assertTrue(env.authClient.deletePasskey.calls == List((userId, "cred-1")))
    },
    test("resetPassword returns the plaintext password when authClient provides one") {
      val env = Env()
      val request = ResetPasswordRequest(userId, 43200L, Some(DeliveryChannel.show))
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        _ <- env.authClient.resetPassword.succeedsWith(Some("Temp1234!"))
        result <- env.service.resetPassword(request)
      yield assertTrue(
        result.contains("Temp1234!"),
        env.authClient.resetPassword.calls == List((userId, 43200L, Some(DeliveryChannel.show))),
      )
    },
    test("resetPassword returns None when the password is delivered out of band") {
      val env = Env()
      val request = ResetPasswordRequest(userId, 43200L, Some(DeliveryChannel.email))
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        _ <- env.authClient.resetPassword.succeedsWith(None)
        result <- env.service.resetPassword(request)
      yield assertTrue(result.isEmpty)
    },
    test("setPassword delegates to authClient") {
      val env = Env()
      for
        _ <- env.moves.unfinished.succeedsWith(false)
        _ <- env.authClient.setPassword.succeedsWith(())
        _ <- env.service.setPassword(userId, "Secret123!")
      yield assertTrue(env.authClient.setPassword.calls == List((userId, "Secret123!")))
    },
    suite("a user with an unfinished move (versolauth/versola#1051)")(
      test("a change the cell refuses because the move began after central looked is UserMoving too, not a failure") {
        val env = Env()
        for
          _ <- env.moves.unfinished.succeedsWith(false)
          _ <- env.userRepository.find.succeedsWith(Some(indexRecord))
          _ <- env.authClient.setPassword.failsWith(AuthClient.UserLeavingCell)
          _ <- env.authClient.patchUserClaims.failsWith(AuthClient.UserLeavingCell)
          _ <- env.authClient.deleteUser.failsWith(AuthClient.UserLeavingCell)
          set <- env.service.setPassword(userId, "Secret123!").either
          claims <- env.service.patchClaims(userId, Json.Obj()).either
          delete <- env.service.delete(userId).either
        yield assertTrue(
          set == Left(UserMoving),
          claims == Left(UserMoving),
          delete == Left(UserMoving),
          env.userRepository.delete.calls.isEmpty,
        )
      },
      test("every change of the user is refused with UserMoving and reaches neither auth nor the directory nor the outbox") {
        val env = Env()
        for
          _ <- env.moves.unfinished.succeedsWith(true)
          _ <- env.userRepository.find.succeedsWith(Some(indexRecord))
          patch <- env.service.patch(PatchUserRequest(userId, Some(Patch.Modified(email)), None, None)).either
          claims <- env.service.patchClaims(userId, Json.Obj()).either
          delete <- env.service.delete(userId).either
          roles <- env.service.updateRoles(UpdateUserRolesRequest(userId, tenantId, Set(RoleId("admin")), Set.empty)).either
          revoke <- env.service.revokeRefreshTokens(userId, ClientId("app")).either
          rename <- env.service.renamePasskey(RenamePasskeyRequest(userId, "cred-1", None)).either
          deletePasskey <- env.service.deletePasskey(userId, "cred-1").either
          reset <- env.service.resetPassword(ResetPasswordRequest(userId, 43200L, None)).either
          set <- env.service.setPassword(userId, "Secret123!").either
        yield assertTrue(
          List(patch, claims, delete, roles, revoke, rename, deletePasskey, set).forall(_ == Left(UserMoving)),
          reset == Left(UserMoving),
          env.userRepository.replaceIdentifiers.calls.isEmpty,
          env.userRepository.delete.calls.isEmpty,
          env.userRepository.enqueueRoleUpdate.calls.isEmpty,
          env.authClient.patchUserClaims.calls.isEmpty,
          env.authClient.deleteUser.calls.isEmpty,
          env.authClient.revokeRefreshTokens.calls.isEmpty,
          env.authClient.renamePasskey.calls.isEmpty,
          env.authClient.deletePasskey.calls.isEmpty,
          env.authClient.resetPassword.calls.isEmpty,
          env.authClient.setPassword.calls.isEmpty,
        )
      },
      test("a user that does not exist is not found, whatever its moves") {
        val env = Env()
        for
          _ <- env.userRepository.find.succeedsWith(None)
          result <- env.service.delete(userId).either
        yield assertTrue(result == Left(UserNotFound), env.moves.unfinished.calls.isEmpty)
      },
      test("reads are not blocked: the user is found and its roles, sessions and passkeys are read") {
        val env = Env()
        for
          _ <- env.moves.unfinished.succeedsWith(true)
          _ <- env.authClient.getUserRoles.succeedsWith(List(RoleId("admin")))
          _ <- env.authClient.listPasskeys.succeedsWith(Nil)
          roles <- env.service.getRoles(userId, tenantId)
          passkeys <- env.service.listPasskeys(userId)
        yield assertTrue(roles == List(RoleId("admin")), passkeys.isEmpty, env.moves.unfinished.calls.isEmpty)
      },
    ),
  )
