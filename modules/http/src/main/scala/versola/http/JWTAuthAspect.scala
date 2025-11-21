package versola.http

import com.nimbusds.jose.crypto.{ECDSAVerifier, MACVerifier, RSASSAVerifier}
import com.nimbusds.jose.jwk.{ECKey, JWKSet, OctetSequenceKey, RSAKey}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import zio.http.*
import zio.json.ast.Json
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Clock, ZIO}

object JWTAuthAspect:
  def authorize[A: JWTClaimDecoder]: HandlerAspect[JWKSet & Tracing, A] =
    HandlerAspect.interceptIncomingHandler:
      Handler.fromFunctionZIO[Request]: request =>
        request.header(Header.Authorization.Bearer) match
          case Some(header) =>
            for
              now <- Clock.instant

              jws <- ZIO.attempt(SignedJWT.parse(header.token.stringValue))
                .orElseFail(unauthorized(description = "invalid token format"))

              keyId <- checkHeader(jws)

              claims <- ZIO.fromNullable(JWTClaimsSet.parse(jws.getPayload.toJSONObject))
                .orElseFail(unauthorized(description = "invalid or missing claims"))

              expDate <- ZIO.fromNullable(claims.getExpirationTime)
                .orElseFail(unauthorized(description = "missing exp claim"))

              _ <- ZIO.unit.when(expDate.toInstant.isAfter(now))
                .someOrFail(unauthorized(description = "token expired"))

              jwk <- ZIO.serviceWithZIO[JWKSet]: jwks =>
                ZIO.fromNullable(jwks.getKeyByKeyId(keyId))
                  .orElseFail(unauthorized(description = "no key with such 'kid'"))

              verifier = jwk match
                case key: RSAKey =>
                  RSASSAVerifier(key)

                case key: ECKey =>
                  ECDSAVerifier(key)

                case key: OctetSequenceKey =>
                  MACVerifier(key)

              isOk <- ZIO.attemptBlocking(jws.verify(verifier))
                .orElseFail(unauthorized(description = "invalid signature"))

              _ <- ZIO.unit.when(isOk)
                .someOrFail(unauthorized(description = "invalid signature"))

              decoder = summon[JWTClaimDecoder[A]]

              tracing <- ZIO.service[Tracing]

              decodedClaims <- decoder.decode(claims).mapError(unauthorized)
              attributes <- decoder.attributes(claims).mapError(unauthorized)

              _ <- ZIO.foreachDiscard(attributes)((key, value) =>
                tracing.setAttribute(key, value),
              )
            yield (request, decodedClaims)

          case None =>
            ZIO.fail(unauthorized(description = "token not provided"))

  private def checkHeader(jws: SignedJWT): ZIO[Any, Response, String] =
    for
      keyId <- ZIO.fromNullable(jws.getHeader.getKeyID)
        .orElseFail(unauthorized(description = "kid not provided"))

      typ <- ZIO.fromNullable(jws.getHeader.getType)
        .orElseFail(unauthorized(description = "typ not provided"))

      _ <- ZIO.unit.when(typ.getType == "at+jwt")
        .someOrFail(unauthorized(description = "typ must be 'at+jwt'"))
    yield keyId

  private def unauthorized(description: String) =
    Response(
      status = Status.Unauthorized,
      headers = Headers(
        Header.WWWAuthenticate.Bearer(
          realm = "auth",
          errorDescription = Some(description),
        ),
        Header.ContentType(MediaType.application.json),
      ),
      body = Body.from(
        Json.Obj(
          "name" -> Json.Str("Unauthorized"),
          "message" -> Json.Str(description),
        ),
      )
    )

