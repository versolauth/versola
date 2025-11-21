package versola.util

import zio.{UIO, ZIO}

trait Generator[A]:
  def generateUnsafe(): A
  def generate(): UIO[A] = ZIO.succeed(generateUnsafe())


object Generator:
  def constant[A](a: A): Generator[A] = () => a
