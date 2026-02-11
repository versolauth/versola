package versola.util

import com.fasterxml.uuid.Generators
import com.fasterxml.uuid.impl.UUIDUtil
import zio.prelude.Equal
import zio.schema.Schema

import java.time.Instant
import java.util.UUID

trait UUIDv7:
  opaque type Type <: UUID = UUID
  
  given Schema[Type] = Schema.primitive[String].transformOrFail(
    string => util.Try(UUIDUtil.uuid(string)).toEither.left.map(_.getMessage),
    uuid => Right(uuid.toString)
  )
  
  inline def apply(id: UUID): Type = id
  inline def wrapAll[F[_]](value: F[UUID]): F[Type] = value

  def fromInstant(now: Instant): Type = 
    Generators.timeBasedEpochGenerator().construct(now.toEpochMilli)
    
  def parse(s: String): Either[Throwable, Type] =
    util.Try(UUIDUtil.uuid(s)).toEither
    
  extension (uuid: Type)
    def createdAt: Instant = Instant.ofEpochMilli(UUIDUtil.extractTimestamp(uuid))

  given Equal[Type] = _ == _
