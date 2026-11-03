package versola.loadgen.antifraud

import zio.http.*
import zio.{Duration, Ref, UIO, ZIO, durationInt}

/** A stand-in for the part of `auth` that the antifraud loadgen drives: the phone sign-in with a proof-of-work challenge
  * (as `ConversationController` and `ConversationService` answer it, with the pause for a client that cannot do work),
  * a fake send that is told from a real one only by what reaches the SMS stub, and a `/metrics`.
  *
  * Every conversation remembers the instance that began it, and every call is recorded with its instance, so that a test
  * can tell which instance a session was on. The instances are told apart by the first segment of the path (the client
  * is given `http://a.test/i1` and `http://a.test/i2` as the `auth` urls, which it extends with the endpoint): one sim
  * stands for all of them, [[routesAt]] mounts it under the names.
  *
  * @param difficulty    zero bits the challenge asks of a request that is not fake; none: no challenge at all
  * @param kind          the kind of challenge put (`pow`; anything else is one a client cannot solve)
  * @param asksToHonour  asks of a plain client after the one that left it pending that are answered with the same page
  *                      before the next is honoured (the pause not yet over)
  * @param fakePrefix    a number that starts with these digits is on the fake list: not challenged, nothing is sent
  * @param realDelay     what a real send takes (the stub's `response-delay`, say)
  * @param fakeDelay     what a fake send takes
  */
final class AuthSim(
    stub: SmsStub,
    difficulty: Option[Int] = None,
    kind: String = "pow",
    asksToHonour: Int = 1,
    fakePrefix: Option[String] = None,
    realDelay: Duration = Duration.Zero,
    fakeDelay: Duration = Duration.Zero,
    conversations: Ref[Map[String, AuthSim.Conversation]],
    calls: Ref[Vector[AuthSim.Call]],
):
  import AuthSim.*

  def seen: UIO[Vector[Call]] = calls.get

  private def host(request: Request): String = request.url.path.segments.headOption.getOrElse("?")

  private def cookie(request: Request): Option[String] = request.cookie("SSO_CONVERSATION").map(_.content)

  private def page(conversation: Conversation): String =
    val (step, pending) = conversation.step match
      case "credential" => ("credential", false)
      case "pending" => ("otp", true)
      case "sent" => ("otp", false)
      case other => (other, false)
    s"""<html><head><meta name="versola-step" content="$step"><script>window.__VERSOLA_FORM__ = {"csrf":"csrf-1","step":{"type":"$step","pending":$pending}};</script></head><body></body></html>"""

  private val toChallenge = Response.seeOther(URL.decode("/challenge").toOption.get)

  private def answer(inline: Boolean, otp: Boolean): Response =
    if inline then Response.json(s"""{"next":"/challenge","otp":$otp}""") else toChallenge

  private def send(phone: String): UIO[Unit] =
    if fakePrefix.exists(phone.filter(_.isDigit).startsWith) then ZIO.sleep(fakeDelay)
    else ZIO.succeed(stub.accept(phone, "Your code is 123456")) *> ZIO.sleep(realDelay)

  private def isFake(phone: String): Boolean = fakePrefix.exists(phone.filter(_.isDigit).startsWith)

  private def update(id: String)(change: Conversation => Conversation): UIO[Unit] = conversations.update(_.updatedWith(id)(_.map(change)))

  /** The same sim as each of the named instances, `/<name>/authorize` and so on. */
  def routesAt(names: String*): Routes[Any, Nothing] =
    names.map(name => routes.nest(zio.http.codec.PathCodec.literal(name))).reduce(_ ++ _)

  private val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "authorize" -> handler: (request: Request) =>
        for
          id <- conversations.modify: all =>
            val id = s"conversation-${all.size}"
            (id, all + (id -> Conversation(host(request), "credential", None, 0)))
          _ <- calls.update(_ :+ Call(host(request), "GET", "/authorize", id, Map.empty))
        yield toChallenge.addCookie(Cookie.Response("SSO_CONVERSATION", id)),
      Method.GET / "challenge" -> handler: (request: Request) =>
        for
          id = cookie(request).getOrElse("")
          conversation <- conversations.get.map(_.getOrElse(id, Conversation("?", "credential", None, 0)))
          _ <- calls.update(_ :+ Call(host(request), "GET", "/challenge", id, Map.empty))
        yield Response.text(page(conversation)),
      Method.POST / "challenge" / "phone" -> handler: (request: Request) =>
        for
          form <- request.body.asString.map(parseForm).orDie
          id = cookie(request).getOrElse("")
          _ <- calls.update(_ :+ Call(host(request), "POST", "/challenge/phone", id, form))
          conversation <- conversations.get.map(_.getOrElse(id, Conversation("?", "credential", None, 0)))
          phone = form.getOrElse("phone", "")
          inline = form.contains("proof_inline")
          response <- difficulty.filterNot(_ => isFake(phone)) match
            case None => send(phone) *> update(id)(_.copy(step = "sent")).as(answer(inline, otp = true))
            case Some(bits) if inline =>
              (form.get("proof_token"), form.get("proof_solution")) match
                case (Some(token), Some(solution)) if conversation.token.contains(token) && Pow.isSolution(s"seed-$id", solution, bits) =>
                  send(phone) *> update(id)(_.copy(step = "sent")).as(answer(inline, otp = true))
                case _ =>
                  val token = s"token-$id-${conversation.asks}"
                  update(id)(c => c.copy(token = Some(token), asks = c.asks + 1)).as(
                    Response.json(s"""{"challenge":{"kind":"$kind","token":"$token","params":{"seed":"seed-$id","difficulty":"$bits"}},"otp":false}"""),
                  )
            case Some(_) => update(id)(_.copy(step = "pending", asks = 0, phone = phone)).as(toChallenge)
        yield response,
      Method.POST / "challenge" / "otp" / "resend" -> handler: (request: Request) =>
        for
          form <- request.body.asString.map(parseForm).orDie
          id = cookie(request).getOrElse("")
          _ <- calls.update(_ :+ Call(host(request), "POST", "/challenge/otp/resend", id, form))
          honoured <- conversations.modify: all =>
            all.get(id) match
              case Some(c) if c.step == "pending" =>
                if c.asks >= asksToHonour then (true, all.updated(id, c.copy(step = "sent"))) else (false, all.updated(id, c.copy(asks = c.asks + 1)))
              case _ => (false, all)
          // the honoured ask is the one that sends
          phone <- conversations.get.map(_.get(id).fold("")(_.phone))
          _ <- ZIO.when(honoured)(send(phone))
        yield toChallenge,
      Method.GET / "metrics" -> handler: (request: Request) =>
        calls.update(_ :+ Call(host(request), "GET", "/metrics", "", Map.empty)).as(Response.text("antifraud_events_enqueued_total 1.0\nantifraud_event_queue_size 2.0\n")),
    )

object AuthSim:
  /** @param step `credential`, `pending` (challenged, no code sent) or `sent`
    * @param asks challenges issued, or asks made while pending */
  case class Conversation(host: String, step: String, token: Option[String], asks: Int, phone: String = "")

  case class Call(host: String, method: String, path: String, conversation: String, form: Map[String, String])

  def make(
      stub: SmsStub,
      difficulty: Option[Int] = None,
      kind: String = "pow",
      asksToHonour: Int = 1,
      fakePrefix: Option[String] = None,
      realDelay: Duration = Duration.Zero,
      fakeDelay: Duration = Duration.Zero,
  ): UIO[AuthSim] =
    for
      conversations <- Ref.make(Map.empty[String, Conversation])
      calls <- Ref.make(Vector.empty[Call])
    yield AuthSim(stub, difficulty, kind, asksToHonour, fakePrefix, realDelay, fakeDelay, conversations, calls)

  private def parseForm(body: String): Map[String, String] =
    body
      .split('&')
      .iterator
      .filter(_.contains('='))
      .map: field =>
        val separator = field.indexOf('=')
        java.net.URLDecoder.decode(field.substring(0, separator), "UTF-8") -> java.net.URLDecoder.decode(field.substring(separator + 1), "UTF-8")
      .toMap
