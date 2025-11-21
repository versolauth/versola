package versola.http

import com.nimbusds.jose.jwk.JWKSet
import zio.http.codec.{HttpCodecError, HttpContentCodec}
import zio.http.{Response, Routes, Status}
import zio.json.*
import zio.json.ast.Json
import zio.schema.*
import zio.schema.codec.json.*
import zio.{FiberRef, UIO, URIO}

import scala.util.control.NoStackTrace

trait Controller:
  type Env >: Nothing
  
  def routes: Routes[Env, Nothing]

  private case object MyException extends RuntimeException, NoStackTrace
  
  def emptyJsonCodec[A]: HttpContentCodec[A] = HttpContentCodec.json
    .only[A](using Schema.fail("No schema"))

  given HttpContentCodec[Json] = HttpContentCodec.json.only[Json]

  given Schema[JWKSet] = Schema[Json].transform(
    json => JWKSet.parse(json.toString()),
    _.toString.fromJson[Json].toOption.get,
  )

  given HttpContentCodec[JWKSet] = HttpContentCodec.json.only[JWKSet]

  extension (ex: Throwable)
    def asResponse: UIO[Response] =
      Controller.exceptions.set(Some(ex)).as(
        ex match
          case ex: HttpCodecError => Response.json(s"""{"name": "${ex.getClass.getName}", "message": "${ex.message}"""")
          case ex: Throwable => Response.json("{}").status(Status.InternalServerError)
      )

object Controller:
  val exceptions: FiberRef[Option[Throwable]] =
    zio.Unsafe.unsafe(unsafe => FiberRef.unsafe.make(None)(using unsafe))