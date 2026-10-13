#!/bin/sh
# Generates a throwaway CA and a server certificate valid for the DNS name `localhost` only (so
# connecting to 127.0.0.1 fails hostname verification -- PostgresTlsConnectionSpec relies on that),
# then starts Postgres as the stock image would. Everything is regenerated on every start, so
# nothing is committed and nothing expires. The CA is copied to /tls-out when that is mounted
# (services.yml mounts ./target/postgres-tls there), which is where the spec reads it.
set -eu

dir=/var/lib/postgresql/tls
mkdir -p "$dir"
cd "$dir"

openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=versola-test-ca" \
  -keyout ca.key -out ca.crt 2>/dev/null
openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" -keyout server.key -out server.csr 2>/dev/null
printf 'subjectAltName=DNS:localhost' > san.ext
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 3650 \
  -extfile san.ext -out server.crt 2>/dev/null

chown postgres:postgres server.key server.crt
chmod 600 server.key
chmod 644 server.crt ca.crt

if [ -d /tls-out ]; then
  cp ca.crt /tls-out/ca.crt
  chmod 644 /tls-out/ca.crt
fi

exec docker-entrypoint.sh "$@"
