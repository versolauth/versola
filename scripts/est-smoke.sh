#!/usr/bin/env bash
#
# Smoke runbook for EST (RFC 7030) client certificates against a running Versola. It is NOT run by CI or by anything
# else here: an operator runs it on purpose, against a stand they name.
#
# What it does, in order, with a client it creates and always deletes:
#   1. registers a service client with `estEnrollment` (tls_client_auth, certificate-bound tokens);
#   2. creates a one-time enrolment token;
#   3. enrols over the issuer origin (simpleenroll) with a key and a PKCS#10 made here;
#   4. asks /token over the mutual-TLS origin with that certificate, and checks the token is bound to it (cnf x5t#S256);
#   5. renews over the mutual-TLS origin (simplereenroll) with a new key -- waiting for the renew window if it is not
#      open yet (a certificate renews only in the last part of its life, a third by default; see the variables below);
#   6. checks the old certificate still authenticates and its token stays bound to it, and the new one likewise;
#   7. revokes the right to renew and checks the next renewal is 401;
#   8. deletes the client.
#
# Variables (all from the environment; nothing is read from arguments, and no value below is ever printed):
#   AUTH_URL                 issuer origin of auth, e.g. https://id.example.com
#   AUTH_MTLS_URL            auth's mutual-TLS origin, e.g. https://id-mtls.example.com
#   CENTRAL_ADMIN_URL        central's admin API (the console's /central proxy or central itself)
#   CENTRAL_ADMIN_SECRET     the central resource secret (HTTP Basic password; the user name is `central`)
#   EST_SMOKE_ALLOWED_HOSTS  comma-separated host names this run may touch. REQUIRED: the script refuses unless the host of
#                            each of the three URLs is listed, so it cannot be pointed at a stand by mistake.
#   EST_SMOKE_TENANT         tenant the test client is created in (default: default)
#   EST_SMOKE_SCOPE          scope the client may be granted (default: openid)
#   EST_SMOKE_CA             PEM file to trust for the servers' TLS (optional; the system trust store otherwise)
#   EST_SMOKE_MAX_WAIT_SECONDS  how long to wait for the renew window to open (default: 0 = fail if it is not open).
#                            The window is a part of the certificate's life (`client-identity.renew-window`, a third by
#                            default of `client-certificates.validity`, 14 days): on a stand with default settings the
#                            wait is days, so run against a stand where it is short or set the window to 1.0 there.
#
# Needs: bash, curl, openssl, python3. The key, the token and the certificates live in a private temporary directory that
# is removed on exit; the script prints status lines only, never a token, a key, a secret or a response body.
set -euo pipefail
umask 077

fail() { echo "est-smoke: $*" >&2; exit 1; }
note() { echo "est-smoke: $*"; }

for var in AUTH_URL AUTH_MTLS_URL CENTRAL_ADMIN_URL CENTRAL_ADMIN_SECRET EST_SMOKE_ALLOWED_HOSTS; do
  [ -n "${!var:-}" ] || fail "$var is required"
done
for tool in curl openssl python3; do
  command -v "$tool" >/dev/null || fail "$tool is not installed"
done

host_of() { python3 -c 'import sys, urllib.parse; print(urllib.parse.urlparse(sys.argv[1]).hostname or "")' "$1"; }

allowed=",${EST_SMOKE_ALLOWED_HOSTS//[[:space:]]/},"
for url in "$AUTH_URL" "$AUTH_MTLS_URL" "$CENTRAL_ADMIN_URL"; do
  host=$(host_of "$url")
  [ -n "$host" ] || fail "cannot read the host of a URL"
  case "$allowed" in
    *",$host,"*) ;;
    *) fail "host '$host' is not in EST_SMOKE_ALLOWED_HOSTS; refusing to run" ;;
  esac
done

TENANT=${EST_SMOKE_TENANT:-default}
SCOPE=${EST_SMOKE_SCOPE:-openid}
MAX_WAIT=${EST_SMOKE_MAX_WAIT_SECONDS:-0}
case "$MAX_WAIT" in ''|*[!0-9]*) fail "EST_SMOKE_MAX_WAIT_SECONDS must be a number of seconds" ;; esac

work=$(mktemp -d)
CLIENT_ID="est-smoke-$(openssl rand -hex 4)"
registered=0
cleanup() {
  status=$?
  if [ "$registered" = 1 ]; then
    code=$(curl_central -X DELETE "$CENTRAL_ADMIN_URL/configuration/clients?clientId=$CLIENT_ID" -o /dev/null -w '%{http_code}') || code=error
    note "cleanup: deleted client $CLIENT_ID (HTTP $code)"
  fi
  rm -rf "$work"
  exit "$status"
}
trap cleanup EXIT

CA_ARGS=()
[ -z "${EST_SMOKE_CA:-}" ] || CA_ARGS=(--cacert "$EST_SMOKE_CA")

# The admin credential goes in through a config on stdin, so it is in no process list or log.
curl_central() {
  printf 'user = "central:%s"\n' "$CENTRAL_ADMIN_SECRET" | curl -sS --config - "${CA_ARGS[@]}" "$@"
}

json_get() { python3 -c 'import json, sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], ""))' "$1" "$2"; }

# 1. register
python3 - "$CLIENT_ID" "$TENANT" "$SCOPE" > "$work/register.json" <<'PY'
import json, sys
print(json.dumps({
    "tenantId": sys.argv[2], "id": sys.argv[1], "clientName": {"en": "EST smoke test"},
    "allowedScopes": [sys.argv[3]], "accessTokenTtl": 300, "authMethod": "tls_client_auth",
    "certificateBoundAccessTokens": True, "estEnrollment": True,
}))
PY
code=$(curl_central -X POST "$CENTRAL_ADMIN_URL/configuration/clients/service" -H 'Content-Type: application/json' \
  --data @"$work/register.json" -o /dev/null -w '%{http_code}')
[ "$code" = 201 ] || fail "registering the client answered HTTP $code"
registered=1
note "1. registered $CLIENT_ID (HTTP $code)"

# 2. token
code=$(curl_central -X POST "$CENTRAL_ADMIN_URL/configuration/clients/est/enrollment-tokens?clientId=$CLIENT_ID" -o "$work/token.json" -w '%{http_code}')
[ "$code" = 201 ] || fail "creating the enrolment token answered HTTP $code"
SUBJECT=$(json_get "$work/token.json" subject)
[ -n "$SUBJECT" ] || fail "no subject in the token answer"
note "2. enrolment token created (HTTP $code); subject to ask for: $SUBJECT"

# openssl's -subj lists the RDNs in encoding order, the reverse of RFC 4514's.
SUBJ=$(python3 -c 'import sys; print("".join("/" + p for p in reversed(sys.argv[1].split(","))))' "$SUBJECT")

newkey() { # $1 = name
  openssl ecparam -name prime256v1 -genkey -noout -out "$work/$1.ec.pem" 2>/dev/null
  openssl pkcs8 -topk8 -nocrypt -in "$work/$1.ec.pem" -out "$work/$1.key" 2>/dev/null
  openssl req -new -key "$work/$1.ec.pem" -subj "$SUBJ" -outform DER 2>/dev/null | openssl base64 -e > "$work/$1.csr.b64"
}

# The leaf of a certs-only PKCS#7 answer, whatever order it lists its certificates in.
leaf() { # $1 = response file (base64), $2 = output pem
  openssl base64 -d -in "$1" -out "$1.der" 2>/dev/null
  openssl pkcs7 -inform DER -in "$1.der" -print_certs 2>/dev/null > "$1.all"
  python3 - "$1.all" "$CLIENT_ID" "$2" <<'PY'
import re, subprocess, sys
text = open(sys.argv[1]).read()
for pem in re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", text, re.S):
    subject = subprocess.run(["openssl", "x509", "-noout", "-subject"], input=pem, capture_output=True, text=True).stdout
    if "CN=" + sys.argv[2] in subject.replace(" ", "") or "CN = " + sys.argv[2] in subject:
        open(sys.argv[3], "w").write(pem + "\n")
        sys.exit(0)
sys.exit("no certificate for the client in the answer")
PY
}

thumbprint() { # x5t#S256 of a PEM certificate
  openssl x509 -in "$1" -outform DER | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '='
}

# 3. enrol over the issuer origin
newkey k1
code=$(curl -sS "${CA_ARGS[@]}" -X POST "$AUTH_URL/.well-known/est/simpleenroll" -H 'Content-Type: application/pkcs10' \
  --config <(printf 'user = "%s:%s"\n' "$CLIENT_ID" "$(json_get "$work/token.json" token)") \
  --data-binary @"$work/k1.csr.b64" -o "$work/enrol.b64" -w '%{http_code}')
[ "$code" = 200 ] || fail "simpleenroll answered HTTP $code"
leaf "$work/enrol.b64" "$work/c1.pem"
note "3. enrolled (HTTP $code); certificate 1 thumbprint $(thumbprint "$work/c1.pem")"

# 4. /token over the mutual-TLS origin
token_with() { # $1 = cert, $2 = key, $3 = output file
  curl -sS "${CA_ARGS[@]}" --cert "$1" --key "$2" -X POST "$AUTH_MTLS_URL/token" \
    --data-urlencode grant_type=client_credentials --data-urlencode "client_id=$CLIENT_ID" --data-urlencode "scope=$SCOPE" \
    -o "$3" -w '%{http_code}'
}
bound_to() { # $1 = token response, prints the thumbprint the access token is bound to
  python3 - "$1" <<'PY'
import base64, json, sys
token = json.load(open(sys.argv[1]))["access_token"]
payload = token.split(".")[1]
print(json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))).get("cnf", {}).get("x5t#S256", ""))
PY
}
# The client reaches auth's cache after a sync, which may lag a registration by a few seconds.
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  code=$(token_with "$work/c1.pem" "$work/k1.key" "$work/t1.json") && [ "$code" = 200 ] && break
  sleep 3
done
[ "$code" = 200 ] || fail "/token with certificate 1 answered HTTP $code"
[ "$(bound_to "$work/t1.json")" = "$(thumbprint "$work/c1.pem")" ] || fail "the token is not bound to certificate 1"
note "4. /token over the mutual-TLS origin (HTTP $code); the token is bound to certificate 1"

# 5. renew, once the window is open
newkey k2
waited=0
while :; do
  code=$(curl -sS "${CA_ARGS[@]}" --cert "$work/c1.pem" --key "$work/k1.key" -X POST "$AUTH_MTLS_URL/.well-known/est/simplereenroll" \
    -H 'Content-Type: application/pkcs10' --data-binary @"$work/k2.csr.b64" -o "$work/renew.b64" -w '%{http_code}')
  [ "$code" = 403 ] && grep -q "too early" "$work/renew.b64" || break
  [ "$waited" -lt "$MAX_WAIT" ] || fail "the renew window is not open yet ($(tr -d '\n' < "$work/renew.b64" | cut -c1-200)); raise EST_SMOKE_MAX_WAIT_SECONDS or use a stand with a short window"
  note "5. renew window not open; waiting 60 s"
  sleep 60
  waited=$((waited + 60))
done
[ "$code" = 200 ] || fail "simplereenroll answered HTTP $code"
leaf "$work/renew.b64" "$work/c2.pem"
note "5. renewed (HTTP $code); certificate 2 thumbprint $(thumbprint "$work/c2.pem")"

# 6. the old certificate keeps authenticating; each token is bound to its own certificate
code=$(token_with "$work/c1.pem" "$work/k1.key" "$work/t1b.json")
[ "$code" = 200 ] || fail "certificate 1 no longer authenticates (HTTP $code)"
[ "$(bound_to "$work/t1b.json")" = "$(thumbprint "$work/c1.pem")" ] || fail "a token over certificate 1 is not bound to it"
code=$(token_with "$work/c2.pem" "$work/k2.key" "$work/t2.json")
[ "$code" = 200 ] || fail "certificate 2 does not authenticate (HTTP $code)"
[ "$(bound_to "$work/t2.json")" = "$(thumbprint "$work/c2.pem")" ] || fail "a token over certificate 2 is not bound to it"
[ "$(thumbprint "$work/c1.pem")" != "$(thumbprint "$work/c2.pem")" ] || fail "the renewed certificate has the thumbprint of the old one"
note "6. both certificates authenticate; each token is bound to its own certificate (a token is accepted only over the certificate it is bound to)"

# 7. revoke the right to renew
code=$(curl_central -X POST "$CENTRAL_ADMIN_URL/configuration/clients/est/revoke?clientId=$CLIENT_ID" -o /dev/null -w '%{http_code}')
[ "$code" = 204 ] || fail "revoke answered HTTP $code"
newkey k3
code=$(curl -sS "${CA_ARGS[@]}" --cert "$work/c2.pem" --key "$work/k2.key" -X POST "$AUTH_MTLS_URL/.well-known/est/simplereenroll" \
  -H 'Content-Type: application/pkcs10' --data-binary @"$work/k3.csr.b64" -o /dev/null -w '%{http_code}')
[ "$code" = 401 ] || fail "a renewal after the revocation answered HTTP $code, expected 401"
note "7. revoked; a renewal now answers HTTP 401"

note "ok"
