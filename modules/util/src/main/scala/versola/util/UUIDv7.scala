package versola.util

import com.fasterxml.uuid.Generators
import com.fasterxml.uuid.impl.UUIDUtil
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

  def fromInstant(now: Instant): Type = 
    Generators.timeBasedEpochGenerator().construct(now.toEpochMilli)
    
  extension (uuid: Type)
    def createdAt: Instant = Instant.ofEpochMilli(UUIDUtil.extractTimestamp(uuid))

