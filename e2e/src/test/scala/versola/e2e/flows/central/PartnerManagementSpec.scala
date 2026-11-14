package versola.e2e.flows.central

import versola.e2e.support.PartnerFixture.*
import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** What a partner's console does through its edge (#549, #962), on the staged stack: the management routes of `/edge/partner`, which
  * carry the staff member's access token (issued by auth to the console client, in `Versola-Principal`), the operator's approval of
  * what the partner registers, the data the partner's edge is then served, and what auth does with a partner's client and with the
  * tokens it issued.
  *
  * Nothing here stubs a service: the staff token is the real one of a user who signed in through the console client with the
  * certificate of the partner's group (pushed authorization request, login, consent, code exchange), and the partner's client is one
  * the partner asked for and the operator approved.
  */
object PartnerManagementSpec extends CentralApiSpec:

  /** The `error` of a refused token request. */
  private def errorOf(result: TokenResult): String = result match
    case TokenResult.Failure(_, body) => body.fromJson[Json.Obj].toOption.flatMap(_.str("error")).getOrElse(s"unreadable: $body")
    case _: TokenResult.Success => "none: the request succeeded"

  private def resourceRequest(id: String, resource: String, audience: List[String]): Json.Obj =
    Json.Obj(
      "resourceId" -> Json.Str(id),
      "resource" -> Json.Str(resource),
      "audience" -> Json.Arr(audience.map(Json.Str(_))*),
      "endpoints" -> Json.Arr(),
      "audienceRateLimits" -> Json.Arr(),
    )

  /** The claims of a JWT, read without verifying it: what is asked is whether a claim is there. */
  /** The `x5t#S256` of the access token, the thumbprint of the certificate it is bound to. */
  private def thumbprintOf(jwt: String): Option[String] =
    confirmations(jwt).get("cnf").flatMap(_.asObject).flatMap(_.get("x5t#S256")).collect { case Json.Str(value) => value }

  private def confirmations(jwt: String): Json.Obj =
    jwt.split('.').lift(1)
      .flatMap(payload => scala.util.Try(String(java.util.Base64.getUrlDecoder.decode(payload), "UTF-8")).toOption)
      .flatMap(_.fromJson[Json.Obj].toOption)
      .getOrElse(Json.Obj("unreadable" -> Json.Bool(true)))

  private def shopRedirect(partner: Partner): String = s"https://shop.${partner.id}.example/callback"

  def spec = suite("Central API: what a partner's console does through its edge")(
    test("a staff member's token authorises management calls, a call without it or with a forged one is 403, and a change is audited by their name") {
      for
        central <- api
        result <- fixture(central): (a, b, _) =>
          for
            (staff, user) <- staffToken(central, a)
            without <- onSurface(central, a).post("/edge/partner/clients", clientRequest(s"${a.id}-shop", a, Set("openid")))
            forged <- asStaff(central, a, staff.reverse).post("/edge/partner/clients", clientRequest(s"${a.id}-shop", a, Set("openid")))
            // the token of the console of another partner is not this partner's
            (otherStaff, _) <- staffToken(central, b)
            ofAnother <- asStaff(central, a, otherStaff).get("/edge/partner/clients")
            clientId <- approvedClient(central, a, staff, Set("openid", "offline_access"))
            listed <- asStaff(central, a, staff).get("/edge/partner/clients")
            clients <- listed.items("clients")
            synced <- syncedTo(central, a, "/edge/partner/sync/clients", "clients", "id")(clientId)
            presets <- syncedTo(central, a, "/edge/partner/sync/auth-request-presets", "presets", "id")(clientId)
            audit <- eventually(asStaff(central, a, staff).get("/edge/partner/audit").flatMap(_.items("events")))(
              _.exists(_.str("kind").exists(_.contains("partner-client"))),
            )
            otherSynced <- onSurface(central, b).get("/edge/partner/sync/clients").flatMap(ids(_, "clients", "id"))
          yield assertTrue(without.status == Status.Forbidden, forged.status == Status.Forbidden)
            .label("no staff token, or one auth did not sign, is refused: the edge's own token is not enough to manage") &&
            assertTrue(ofAnother.status == Status.Forbidden).label("the staff token of another partner's console is for another client") &&
            assertTrue(clients.exists(c => c.str("id").contains(clientId) && c.obj("approval").flatMap(_.str("status")).contains("approved")))
              .label("what the partner asked for, as the operator approved it") &&
            assertTrue(synced.contains(clientId), presets.contains(clientId))
              .label("an approved client is synced to the partner's edge, with its login preset") &&
            assertTrue(!otherSynced.contains(clientId)).label("and to nobody else's") &&
            assertTrue(
              audit.exists(e => e.str("actorType").contains("partner-staff") && e.str("actorId").contains(user.id.toString)),
              audit.exists(e => e.str("actorType").contains("operator") && !e.has("actorId")),
              audit.forall(e => !e.has("ip") || e.isNull("ip") || e.str("actorType").contains("partner-staff")),
            ).label("the partner's feed names its own staff, and shows the operator's decision without naming the operator")
      yield result
    },
    test("a partner's resource is served to its own edge and never to the operator's, and one outside its domains is refused") {
      for
        central <- api
        result <- fixture(central): (a, b, firstParty) =>
          for
            (staff, _) <- staffToken(central, a)
            clientId <- approvedClient(central, a, staff, Set("openid"))
            resourceId = s"${a.id}-api"
            created <-
              asStaff(central, a, staff).post("/edge/partner/resources", resourceRequest(resourceId, s"https://api.${a.id}.example", List(clientId)))
            foreign <-
              asStaff(central, a, staff).post("/edge/partner/resources", resourceRequest(s"${a.id}-evil", "https://operator.example", List(clientId)))
            spelled <- asStaff(central, a, staff).post(
              "/edge/partner/resources",
              resourceRequest(s"${a.id}-caps", s"https://API.${a.id}.example", List(clientId)),
            )
            // the operator's own resource in the same tenant: what the operator's edge is served
            operatorId = s"${a.id}-operator-api"
            _ <- central.post("/configuration/resources", Fixtures.resource(operatorId, "https://operator-api.example.com", tenantId = a.tenantId))
            own <- syncedTo(central, a, "/edge/partner/sync/resources", "resources", "resourceId")(resourceId)
            ofB <- onSurface(central, b).get("/edge/partner/sync/resources").flatMap(ids(_, "resources", "resourceId"))
            firstPartys <- eventually(
              onSurface(central, firstParty).get("/edge/sync/resources").flatMap(ids(_, "resources", "resourceId")),
            )(_.contains(operatorId))
            _ <- central.delete("/configuration/resources", "resourceId" -> operatorId).ignore
          yield assertTrue(created.status == Status.Created).label(s"the partner's resource: ${created.status} ${created.body}") &&
            assertTrue(foreign.status == Status.BadRequest, spelled.status == Status.BadRequest)
              .label("a host that is not the partner's, or another spelling of its own, is refused") &&
            assertTrue(
              own.contains(resourceId),
              !ofB.contains(resourceId),
            ).label("the partner's edge is served its resource, another partner's is not") &&
            assertTrue(firstPartys.contains(operatorId), !firstPartys.contains(resourceId))
              .label(
                "the operator's own edge is served the operator's resource in the same tenant, and the partner's -- upstream secret and rules -- never",
              )
      yield result
    },
    test(
      "a partner's client issues tokens bound to the certificate of the edge that asked, a refresh through another edge of the group re-binds them, another group's certificate is no credential",
    ) {
      for
        central <- api
        result <- fixture(central): (a, b, _) =>
          for
            (staff, _) <- staffToken(central, a)
            clientId <- approvedClient(central, a, staff, Set("openid", "offline_access"))
            outcome <- withAuth(central): auth =>
              for
                user <- createUser(auth)
                replica1 <- groupCertificate(a, "replica-1")
                replica2 <- groupCertificate(a, "replica-2")
                otherGroup <- groupCertificate(b, "replica-1")
                token <- signIn(auth, clientId, shopRedirect(a), Set("openid", "offline_access"), replica1.urlEncodedPem, user)
                refreshToken = token.refreshToken.getOrElse("")
                // the token is the first replica's: its certificate serves it, another replica's and none do not
                overFirst <- auth.userinfo(token.accessToken, certificate = Some(replica1.urlEncodedPem)).either
                overSecond <- auth.userinfo(token.accessToken, certificate = Some(replica2.urlEncodedPem)).either
                withoutCertificate <- auth.userinfo(token.accessToken).either
                // the second replica refreshes the grant: the client is the group's, so it authenticates, and the new token is its own
                moved <- auth.refresh(refreshToken, Some(clientId), Some(""), certificate = Some(replica2.urlEncodedPem))
                pushedByOtherGroup <- auth.pushAuthorizationRequest(clientId, "", shopRedirect(a), certificate = Some(otherGroup.urlEncodedPem))
                pushedWithoutCertificate <- auth.pushAuthorizationRequest(clientId, "", shopRedirect(a))
                refreshedByOtherGroup <- auth.refresh(refreshToken, Some(clientId), Some(""), certificate = Some(otherGroup.urlEncodedPem))
                movedOver <- moved match
                  case success: TokenResult.Success =>
                    for
                      overMoved <- auth.userinfo(success.accessToken, certificate = Some(replica2.urlEncodedPem)).either
                      overOld <- auth.userinfo(success.accessToken, certificate = Some(replica1.urlEncodedPem)).either
                    yield Some((success, overMoved, overOld))
                  case _ => ZIO.none
              yield (
                token,
                overFirst,
                overSecond,
                withoutCertificate,
                moved,
                movedOver,
                pushedByOtherGroup,
                pushedWithoutCertificate,
                refreshedByOtherGroup,
                (replica1.thumbprint, replica2.thumbprint),
              )
            (
              token,
              overFirst,
              overSecond,
              withoutCertificate,
              moved,
              movedOver,
              pushedByOtherGroup,
              pushedWithoutCertificate,
              refreshedByOtherGroup,
              (first, second),
            ) =
              outcome
          yield assertTrue(
            token.refreshToken.isDefined,
            first != second,
          ).label("the client has offline access, and the replicas hold different certificates") &&
            assertTrue(thumbprintOf(token.accessToken).contains(first))
              .label("the token is bound (cnf x5t#S256) to the certificate of the replica that asked for it") &&
            assertTrue(
              overFirst.exists(_.isInstanceOf[UserinfoResult.Success]),
              !overSecond.exists(_.isInstanceOf[UserinfoResult.Success]),
              !withoutCertificate.exists(_.isInstanceOf[UserinfoResult.Success]),
            ).label("only that certificate serves it: another replica's, and none, are refused") &&
            assertTrue(moved.isInstanceOf[TokenResult.Success]).label("the grant is refreshed through another replica of the group") &&
            assertTrue(
              movedOver.exists { case (success, overMoved, overOld) =>
                thumbprintOf(success.accessToken).contains(second) &&
                overMoved.exists(_.isInstanceOf[UserinfoResult.Success]) &&
                !overOld.exists(_.isInstanceOf[UserinfoResult.Success])
              },
            ).label("and the new token is bound to that replica's certificate: re-bound, not unbound") &&
            assertTrue(
              pushedByOtherGroup.isInstanceOf[PushedAuthorizationResult.Failure],
              pushedWithoutCertificate.isInstanceOf[PushedAuthorizationResult.Failure],
              errorOf(refreshedByOtherGroup) == "invalid_client",
            ).label("the certificate of another partner's group, or none, does not authenticate this partner's client")
      yield result
    },
    test(
      "suspending a partner makes auth forget its clients and revokes nothing: a grant issued before runs out on its own, and works again after resuming",
    ) {
      for
        central <- api
        result <- fixture(central): (a, _, _) =>
          for
            (staff, _) <- staffToken(central, a)
            clientId <- approvedClient(central, a, staff, Set("openid", "offline_access"))
            outcome <- withAuth(central): auth =>
              for
                user <- createUser(auth)
                certificate <- groupCertificate(a, "replica-1")
                header = certificate.urlEncodedPem
                token <- signIn(auth, clientId, shopRedirect(a), Set("openid", "offline_access"), header, user)
                refreshToken = token.refreshToken.getOrElse("")
                suspended <- central.post("/configuration/partners/suspend", Json.Obj("id" -> Json.Str(a.id), "note" -> Json.Str("e2e")))
                _ <- authKnows(central, a)(known = false)
                // the client is unknown to auth: nothing is refreshed through it, a new code flow does not start
                during <- auth.refresh(refreshToken, Some(clientId), Some(""), certificate = Some(header))
                pushedDuring <- auth.pushAuthorizationRequest(clientId, "", shopRedirect(a), certificate = Some(header))
                // the access token is bound to the certificate that asked for it, and auth has to know the client to check that: while the
                // client is unknown, the token is not served (it is not revoked, see after the resume)
                accessDuring <- auth.userinfo(token.accessToken, certificate = Some(header)).either
                edgeDuring <- onSurface(central, a).get("/edge/partner/me")
                resumed <- central.post("/configuration/partners/resume", Json.Obj("id" -> Json.Str(a.id)))
                _ <- authKnows(central, a)(known = true)
                // nothing was revoked: the refresh token that was issued before the suspension is good again
                after <- auth.refresh(refreshToken, Some(clientId), Some(""), certificate = Some(header))
                accessAfter <- auth.userinfo(token.accessToken, certificate = Some(header)).either
              yield (suspended, during, pushedDuring, accessDuring, edgeDuring, resumed, after, accessAfter)
            (suspended, during, pushedDuring, accessDuring, edgeDuring, resumed, after, accessAfter) = outcome
          yield assertTrue(suspended.status == Status.NoContent).label(s"${suspended.status} ${suspended.body}") &&
            assertTrue(errorOf(during) == "invalid_client", pushedDuring.isInstanceOf[PushedAuthorizationResult.Failure])
              .label("a suspended partner's client is unknown to auth, whatever certificate of the group is presented") &&
            assertTrue(!accessDuring.exists(_.isInstanceOf[UserinfoResult.Success]))
              .label("an access token of the partner is bound to a certificate and auth does not know the client: it is not served meanwhile") &&
            assertTrue(edgeDuring.status == Status.Forbidden).label("the partner's edge is refused") &&
            assertTrue(
              resumed.status == Status.NoContent,
              after.isInstanceOf[TokenResult.Success],
              accessAfter.exists(_.isInstanceOf[UserinfoResult.Success]),
            )
              .label("after resuming the client is back: the grant and the access token that were never revoked work again")
      yield result
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(300.seconds)
