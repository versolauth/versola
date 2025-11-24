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