package versola.user

import versola.util.http.Controller
import zio.stream.{UStream, ZStream}
import zio.{IO, ZIO, ZLayer}

import java.nio.file.{Files, Path, StandardOpenOption}

trait FileService:
  def write(path: Path, data: UStream[Byte]): IO[Throwable, Unit]

  def read(path: Path): IO[Throwable, Option[UStream[Byte]]]

  def delete(path: Path): IO[Throwable, Unit]

  def readAsString(path: Path): IO[Throwable, String]

  def listDirectories(path: Path): IO[Throwable, Vector[Path]]

  def listFiles(path: Path, extension: String): IO[Throwable, Vector[Path]]

  def exists(path: Path): IO[Throwable, Boolean]

  def isDirectory(path: Path): IO[Throwable, Boolean]

object FileService:
  def live = ZLayer.succeed(Impl())

  class Impl() extends FileService:
    override def write(path: Path, data: UStream[Byte]) =
      ZIO.scoped:
        ZIO.blocking:
          for
            _ <- ZIO.attempt(Files.createDirectories(path.getParent))
            out <- ZIO.fromAutoCloseable:
              ZIO.attempt:
                Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            in <- data.toInputStream

            _ <- ZIO.attempt:
              in.transferTo(out)
          yield ()

    override def read(path: Path): IO[Throwable, Option[UStream[Byte]]] =
      ZIO.scoped:
        ZIO.blocking:
          for
            exists <- ZIO.attempt(Files.exists(path))
            stream = Option.when(exists):
              ZStream.fromFile(path.toFile).orDie
          yield stream

    override def delete(path: Path): IO[Throwable, Unit] =
      ZIO.attemptBlocking:
        if Files.exists(path) then Files.delete(path)
      .unit

    override def readAsString(path: Path): IO[Throwable, String] =
      ZIO.attemptBlocking(Files.readString(path))

    override def listDirectories(path: Path): IO[Throwable, Vector[Path]] =
      ZIO.attemptBlocking {
        if Files.exists(path) && Files.isDirectory(path) then
          import scala.jdk.CollectionConverters.*
          Files.list(path)
            .filter(Files.isDirectory(_))
            .toList
            .asScala
            .toVector
        else
          Vector.empty
      }

    override def listFiles(path: Path, extension: String): IO[Throwable, Vector[Path]] =
      ZIO.attemptBlocking {
        if Files.exists(path) && Files.isDirectory(path) then
          import scala.jdk.CollectionConverters.*
          Files.list(path)
            .filter(p => Files.isRegularFile(p) && p.toString.endsWith(extension))
            .toList
            .asScala
            .toVector
        else
          Vector.empty
      }

    override def exists(path: Path): IO[Throwable, Boolean] =
      ZIO.attemptBlocking(Files.exists(path))

    override def isDirectory(path: Path): IO[Throwable, Boolean] =
      ZIO.attemptBlocking(Files.isDirectory(path))
