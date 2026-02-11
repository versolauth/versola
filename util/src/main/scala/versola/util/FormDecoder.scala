package versola.util

import zio.http.Form

trait FormDecoder[A]:
  def decode(form: Form): Either[String, A]


object FormDecoder:
  def single[A](form: Form, name: String, parse: String => Either[String, A]): Either[String, A] =
    form.get(name).toRight(s"Field '$name' is missing")
      .flatMap(_.stringValue.toRight(s"Field '$name' is not a string"))
      .flatMap(parse)
