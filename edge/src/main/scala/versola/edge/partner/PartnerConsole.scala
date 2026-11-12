package versola.edge.partner

import versola.util.http.Observability
import zio.http.*
import zio.{Task, UIO, ZIO}

import java.nio.file.{Files, Path as JPath}

/** What a browser needs to reach the partner's console, on the edge's own origin (#564): the fixed sign-in and sign-out paths and
  * the console's static files.
  *
  * `GET /partner/login` and `GET /partner/logout` send the browser to `/login/<presetId>` and `/logout/<presetId>` of the console's
  * preset, which central created with the partner and `/edge/partner/me` names. The console cannot know the preset before it has
  * signed in, which is why these two paths are fixed and the edge does the looking up.
  *
  * The files are those of `central-ui`'s second entry (`partner.html`, `versola-partner.js`), found in `partner.console-directory`.
  * Only a plain file of that directory is served; the API's own paths (`/partner/api`) are never files.
  */
final class PartnerConsole(state: PartnerState, directory: Option[JPath]):

  def routes: Routes[Any, Throwable] =
    Routes(
      Method.GET / "partner" / "login" -> Handler.fromZIO(redirect("login")),
      Method.GET / "partner" / "logout" -> Handler.fromZIO(redirect("logout")),
      Method.GET / "partner" -> Handler.fromZIO(serve(JPath.of("partner.html").nn)),
      Method.GET / "partner" / trailing -> Handler.fromFunctionZIO[(Path, Request)]((rest: Path, _: Request) => files(rest)),
    )

  private def redirect(action: String): UIO[Response] =
    state.me.map: me =>
      val target = URL.decode(s"/$action/${java.net.URLEncoder.encode(me.console.presetId, "UTF-8")}").toOption
      target.fold(Response.status(Status.InternalServerError))(url => Response.seeOther(url).addHeader(Header.CacheControl.NoStore))

  private def files(rest: Path): UIO[Response] =
    val segments = rest.segments.map(_.toString)
    if segments.isEmpty then serve(JPath.of("partner.html").nn)
    else if segments.headOption.contains("api") || segments.exists(segment => segment.isEmpty || segment.startsWith(".") || segment.contains("\\"))
    then notFound
    else serve(JPath.of(segments.head, segments.tail*).nn)

  private val notFound: UIO[Response] = Observability.setError("console_file_not_found").as(Response.status(Status.NotFound))

  /** `relative` of the console directory, if it is a regular file in it. `toRealPath` first, so that a symbolic link out of the
    * directory is not a file of it. */
  private def serve(relative: JPath): UIO[Response] =
    directory match
      case None => notFound
      case Some(root) =>
        ZIO.attemptBlocking:
          val base = root.toRealPath().nn
          // the file's own real path, with every link in it followed: one that leaves the directory is not a file of it
          val file = base.resolve(relative).nn.normalize().nn.toRealPath().nn
          Option.when(file.startsWith(base) && Files.isRegularFile(file) && Files.isReadable(file)):
            val name = file.getFileName.nn.toString
            val extension = name.substring(name.lastIndexOf('.') + 1)
            (Files.readAllBytes(file).nn, name, extension)
        .orElseSucceed(None).flatMap:
          case None => notFound
          case Some((bytes, name, extension)) =>
            MediaType.forFileExtension(extension) match
              case Some(mediaType) if PartnerConsole.Served.contains(extension) =>
                ZIO.succeed(
                  Response(
                    status = Status.Ok,
                    headers = PartnerConsole.SecurityHeaders ++ Headers(Header.ContentType(mediaType), Header.CacheControl.NoCache),
                    body = Body.fromArray(bytes),
                  ),
                )
              case _ => notFound

object PartnerConsole:
  /** What a console build holds. Anything else in the directory is not served. */
  val Served: Set[String] = Set("html", "js", "css", "map", "svg", "png", "ico", "woff2", "json", "txt")

  /** The console is its own origin's only script and style, makes requests to itself, and is shown by no one else's page. */
  val SecurityHeaders: Headers = Headers(
    Header.Custom(
      "Content-Security-Policy",
      "default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    ),
    Header.Custom("X-Content-Type-Options", "nosniff"),
    Header.Custom("X-Frame-Options", "DENY"),
    Header.Custom("Referrer-Policy", "no-referrer"),
  )
