package versola.util

import zio.{IO, ZIO}
import zio.http.Form

trait FormDecoder[A]:
  def decode(form: Form): IO[String, A]


object FormDecoder:
  def single[A](form: Form, name: String, parse: String => Either[String, A]): IO[String, A] =
    ZIO.fromEither:
      form.get(name).toRight(s"Field '$name' is missing")
        .flatMap(_.stringValue.toRight(s"Field '$name' is not a string"))
        .flatMap(parse)

  def optional[A](form: Form, name: String, parse: String => Either[String, A]): IO[String, Option[A]] =
    form.get(name) match
      case None => ZIO.none
      case Some(value) =>
        ZIO.fromEither:
          value.stringValue.toRight(s"Field '$name' is not a string")
            .flatMap(parse)
            .map(Some(_))
