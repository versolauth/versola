package versola.edge.partner

import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

import java.nio.file.{Files, Path}

/** The sign-in and sign-out paths of the console, which the console cannot compute (it has no preset before it has signed in), and
  * its files, served from a directory the operator names and from nowhere else. */
object PartnerConsoleSpec extends ZIOSpecDefault:

  private val me = PartnerMe(
    partner = PartnerMe.Partner("acme", "Acme Pay", None, "active", Json.Obj(), None, "t"),
    operator = PartnerMe.Operator("Bank", None, None, None),
    console = PartnerMe.Console("acme-console", "acme-console"),
    edge = PartnerMe.Edge("edge-1", None),
  )

  private def get(path: String) = Request.get(URL.decode(s"https://login.acme.example$path").toOption.get)

  /** A directory with a console build in it, a file beside it that is not the console's, and a link out of it. */
  private def console: ZIO[Scope & TestClient & Client, Throwable, (Path, Client)] =
    for
      root <- ZIO.attemptBlocking(Files.createTempDirectory("partner-console"))
      outside <- ZIO.attemptBlocking(Files.createTempDirectory("not-the-console"))
      _ <- ZIO.attemptBlocking:
        Files.writeString(root.resolve("partner.html"), "<html>console</html>")
        Files.writeString(root.resolve("versola-partner.js"), "console.log(1)")
        Files.writeString(root.resolve("notes.sh"), "echo no")
        Files.writeString(root.resolve(".secret.js"), "hidden")
        Files.createDirectory(root.resolve("assets"))
        Files.writeString(root.resolve("assets").resolve("logo.svg"), "<svg/>")
        Files.writeString(outside.resolve("secret.js"), "outside")
        Files.createSymbolicLink(root.resolve("escape.js"), outside.resolve("secret.js"))
      client <- ZIO.service[Client]
      _ <- TestClient.addRoutes(PartnerConsole(PartnerState.fixed(me, true), Some(root)).routes.handleError(_ => Response.internalServerError))
    yield (root, client)

  def spec = suite("PartnerConsole")(
    test("/partner/login goes to the login of the console's preset, and /partner/logout to its logout, without the console knowing the preset") {
      for
        (_, client) <- console
        login <- client.batched(get("/partner/login"))
        logout <- client.batched(get("/partner/logout"))
      yield assertTrue(
        login.status == Status.SeeOther,
        login.header(Header.Location).exists(_.url.encode == "/login/acme-console"),
        logout.status == Status.SeeOther,
        logout.header(Header.Location).exists(_.url.encode == "/logout/acme-console"),
      )
    },
    test("the page is served at /partner and /partner/, with a policy that lets nothing but its own files run") {
      for
        (_, client) <- console
        bare <- client.batched(get("/partner"))
        slash <- client.batched(get("/partner/"))
        body <- slash.body.asString
      yield assertTrue(
        bare.status == Status.Ok,
        slash.status == Status.Ok,
        body == "<html>console</html>",
        slash.header(Header.ContentType).exists(_.mediaType == MediaType.text.html),
        slash.headers.get("Content-Security-Policy").exists(_.contains("default-src 'self'")),
        slash.headers.get("X-Frame-Options").contains("DENY"),
        slash.headers.get("X-Content-Type-Options").contains("nosniff"),
      )
    },
    test("a file of the build is served with its type, one in a subdirectory too") {
      for
        (_, client) <- console
        script <- client.batched(get("/partner/versola-partner.js"))
        logo <- client.batched(get("/partner/assets/logo.svg"))
      yield assertTrue(
        script.status == Status.Ok,
        logo.status == Status.Ok,
        script.header(Header.ContentType).exists(_.mediaType.subType.contains("javascript")),
      )
    },
    test("what is not a console file is 404: a type that is not served, a hidden file, a link out of the directory, a path out of it") {
      for
        (_, client) <- console
        script <- client.batched(get("/partner/notes.sh"))
        hidden <- client.batched(get("/partner/.secret.js"))
        link <- client.batched(get("/partner/escape.js"))
        traversal <- client.batched(get("/partner/..%2F..%2Fetc%2Fpasswd"))
        dotdot <- client.batched(get("/partner/assets/../../x.js"))
        missing <- client.batched(get("/partner/nothing.js"))
      yield assertTrue(
        script.status == Status.NotFound,
        hidden.status == Status.NotFound,
        link.status == Status.NotFound,
        traversal.status == Status.NotFound,
        dotdot.status == Status.NotFound,
        missing.status == Status.NotFound,
      )
    },
    test("the API's own paths are never files") {
      for
        (_, client) <- console
        api <- client.batched(get("/partner/api/me"))
      yield assertTrue(api.status == Status.NotFound)
    },
    test("without a directory nothing of the console is served, and the sign-in paths still are") {
      for
        client <- ZIO.service[Client]
        _ <- TestClient.addRoutes(PartnerConsole(PartnerState.fixed(me, true), None).routes.handleError(_ => Response.internalServerError))
        page <- client.batched(get("/partner/"))
        login <- client.batched(get("/partner/login"))
      yield assertTrue(page.status == Status.NotFound, login.status == Status.SeeOther)
    },
  ).provide(TestClient.layer, Scope.default) @@ TestAspect.silentLogging
