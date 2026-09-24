#!/bin/sh
# Tests for entrypoint.sh's gateway/TLS config generation and its AUTH_URL /
# ACME_DIRECTORY / PROXY_MODE / LISTEN_IPV6 validation.
#
# Runs inside nginx:1.30-alpine, the gateway's own base image: that gives the
# same busybox sh the versola-tools image runs entrypoint.sh with, plus nginx
# with ngx_http_acme_module and envsubst, so every generated config is also
# checked with a real `nginx -t` exactly the way the gateway would load it.
# From the repo root:
#
#   docker run --rm -v "$PWD":/w -w /w nginx:1.30-alpine sh docker/versola-tools/entrypoint_test.sh
#
# gen-env.scala (Java) isn't run: a stub stands in for `java`, writes the
# per-service files entrypoint.sh copies afterwards, and records the AUTH_URL
# it was handed so the normalization entrypoint.sh does is checked too.
# gen-env's own output is covered by the Scala side, not here.
set -u

ROOT=$(pwd)
TOOLS="$ROOT/docker/versola-tools"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
PASS=0
FAIL=0

mkdir -p "$WORK/bin"
cat > "$WORK/bin/java" <<'STUB'
#!/bin/sh
read -r target
mkdir -p ".local/env/$target"
for s in auth central edge; do
  echo "# $s.conf (stub)" > ".local/env/$target/$s.conf"
  echo "STUB=1" > ".local/env/$target/$s.generated-secrets.env"
done
printf '%s' "${AUTH_URL:-}" > .local/auth_url_seen
STUB
chmod +x "$WORK/bin/java"

ok()   { PASS=$((PASS + 1)); }
fail() { FAIL=$((FAIL + 1)); echo "FAIL [$CASE]: $*"; }

# gen <case name> [VAR=value ...] -- runs entrypoint.sh in a fresh copy of the
# tools dir (so nothing lands in the repo), output in $OUT, exit code in $RC.
gen() {
  CASE=$1
  shift
  rm -rf "$WORK/tools" "$WORK/out"
  cp -r "$TOOLS" "$WORK/tools"
  OUT="$WORK/out"
  if (cd "$WORK/tools" && env PATH="$WORK/bin:$PATH" VERSION=0.0.0-test OUT_DIR="$OUT" \
        POSTGRES_HOST=127.0.0.1:5432 "$@" sh ./entrypoint.sh) >"$WORK/stdout" 2>"$WORK/stderr"; then
    RC=0
  else
    RC=$?
  fi
}

expect_ok() {
  if [ "$RC" -eq 0 ]; then ok; else fail "expected success, got rc=$RC: $(tail -1 "$WORK/stderr")"; fi
}

# expect_fail <substring of the error> -- and nothing generated.
expect_fail() {
  if [ "$RC" -eq 0 ]; then
    fail "expected failure containing '$1', but it succeeded"
    return
  fi
  if grep -qF -- "$1" "$WORK/stderr"; then ok; else fail "error lacks '$1': $(tail -1 "$WORK/stderr")"; fi
  if [ -e "$OUT/listen.conf" ] || [ -e "$OUT/compose.fragment.yml" ]; then
    fail "bundle was (partly) generated despite the error"
  else
    ok
  fi
}

has_line()  { if grep -qxF -- "$2" "$OUT/$1"; then ok; else fail "$1 lacks line: $2"; fi; }
lacks()     { if grep -qF -- "$2" "$OUT/$1"; then fail "$1 unexpectedly contains: $2"; else ok; fi; }
# lacks_directive: like lacks, but ignores comment lines -- for directives
# whose names the templates also mention in their explanatory comments.
lacks_directive() {
  if grep -v '^[[:space:]]*#' "$OUT/$1" | grep -qF -- "$2"; then fail "$1 has directive: $2"; else ok; fi
}
has()       { if grep -qF -- "$2" "$OUT/$1"; then ok; else fail "$1 lacks: $2"; fi; }
auth_url_seen() {
  seen=$(cat "$WORK/tools/.local/auth_url_seen" 2>/dev/null || true)
  if [ "$seen" = "$1" ]; then ok; else fail "gen-env got AUTH_URL '$seen', expected '$1'"; fi
}

# nginx_check [hosts...] -- installs the generated bundle the way the gateway
# container does (compose mounts + the stock entrypoint's envsubst of
# templates/*.template) and runs `nginx -t` with our own main nginx.conf.
# NGINX_ETC only exists so the test's own logic can be exercised outside
# the nginx image (with a stub nginx); in the image it's /etc/nginx.
NGINX_ETC="${NGINX_ETC:-/etc/nginx}"
cp "$ROOT/docker/gateway/nginx.conf" "$NGINX_ETC"/nginx.conf
nginx_check() {
  rm -f "$NGINX_ETC"/conf.d/*.conf
  cp "$OUT/nginx.conf"        "$NGINX_ETC"/conf.d/default.conf
  cp "$OUT/proxy_params.conf" "$NGINX_ETC"/proxy_params.conf
  cp "$OUT/upstreams.conf"    "$NGINX_ETC"/versola-upstreams.conf
  cp "$OUT/listen.conf"       "$NGINX_ETC"/versola-listen.conf
  NGINX_LOCAL_RESOLVERS=127.0.0.11 envsubst '${NGINX_LOCAL_RESOLVERS}' \
    < "$OUT/acme.conf.template" > "$NGINX_ETC"/conf.d/acme.conf
  if nginx -t -q 2>"$WORK/nginx.err"; then ok; else fail "nginx -t: $(cat "$WORK/nginx.err")"; fi
}

# docker-local's upstreams are container names; make them resolvable here.
if [ -w /etc/hosts ] && ! grep -q ' auth$' /etc/hosts; then
  printf '127.0.0.1 auth\n127.0.0.1 edge\n' >> /etc/hosts
fi

# ---------------------------------------------------------------- happy paths

gen "docker-local" TARGET=docker-local
expect_ok
has_line listen.conf "listen 2821;"
has upstreams.conf "server auth:8080;"
has acme.conf.template "TLS is off"
lacks compose.fragment.yml '${VERSION}'
nginx_check

gen "vps tls, ipv4 only" TARGET=vps AUTH_URL=https://id.example.com PROXY_MODE=nginx
expect_ok
has_line listen.conf "listen 443 ssl;"
has_line listen.conf "acme_certificate letsencrypt id.example.com;"
has_line listen.conf 'ssl_certificate $acme_certificate;'
lacks listen.conf "[::]"
lacks acme.conf.template "[::]"
has_line acme.conf.template 'resolver ${NGINX_LOCAL_RESOLVERS} ipv6=off;'
has acme.conf.template "uri https://acme-v02.api.letsencrypt.org/directory;"
has acme.conf.template 'return 301 https://$host$request_uri;'
has upstreams.conf "server 127.0.0.1:8080;"
has compose.fragment.yml 'profiles: ["gateway"]'
has compose.fragment.yml "versola-gateway:0.0.0-test"
lacks compose.fragment.yml '${VERSION}'
auth_url_seen https://id.example.com
nginx_check

gen "vps tls, ipv6 on, staging acme" TARGET=vps AUTH_URL=https://id.example.com PROXY_MODE=nginx \
  LISTEN_IPV6=on ACME_DIRECTORY=https://acme-staging-v02.api.letsencrypt.org/directory
expect_ok
has_line listen.conf "listen [::]:443 ssl;"
has acme.conf.template "listen [::]:80;"
has_line acme.conf.template 'resolver ${NGINX_LOCAL_RESOLVERS};'
has acme.conf.template "uri https://acme-staging-v02.api.letsencrypt.org/directory;"
nginx_check

gen "vps http" TARGET=vps AUTH_URL=http://1.2.3.4 PROXY_MODE=nginx
expect_ok
has_line listen.conf "listen 80;"
lacks listen.conf "ssl"
has acme.conf.template "TLS is off"
nginx_check

gen "vps external" TARGET=vps AUTH_URL=https://id.example.com:8443 PROXY_MODE=external
expect_ok
has_line listen.conf "listen 127.0.0.1:2821;"
has_line listen.conf "set_real_ip_from 127.0.0.1;"
has_line listen.conf "real_ip_header X-Forwarded-For;"
has acme.conf.template "TLS is off"
auth_url_seen https://id.example.com:8443
nginx_check

gen "realip only in external mode" TARGET=vps AUTH_URL=https://id.example.com PROXY_MODE=nginx
lacks_directive listen.conf "set_real_ip_from"
lacks_directive nginx.conf "set_real_ip_from"

gen "external accepts an IP" TARGET=vps AUTH_URL=https://1.2.3.4 PROXY_MODE=external
expect_ok

# ------------------------------------------------------ AUTH_URL normalization

gen "trailing slash + uppercase are normalized" TARGET=vps AUTH_URL=HTTPS://ID.Example.COM/ PROXY_MODE=nginx
expect_ok
auth_url_seen https://id.example.com
has_line listen.conf "acme_certificate letsencrypt id.example.com;"

# ------------------------------------------------------------- rejections

for mode in nginx external; do
  for url in \
      "https://id.example.com/prefix" "https://id.example.com//" \
      "https://id.example.com?x=1" "https://id.example.com#f" \
      "https://u@id.example.com" "https://a b.com" "https://a.com;x" "https://[::1]"; do
    gen "reject $url ($mode)" TARGET=vps AUTH_URL="$url" PROXY_MODE=$mode
    expect_fail "AUTH_URL"
  done
  gen "reject no scheme ($mode)" TARGET=vps AUTH_URL=id.example.com PROXY_MODE=$mode
  expect_fail "must be http(s)://"
  gen "reject ftp ($mode)" TARGET=vps AUTH_URL=ftp://a.com PROXY_MODE=$mode
  expect_fail "scheme must be http or https"
  gen "reject explicit :443 ($mode)" TARGET=vps AUTH_URL=https://a.com:443 PROXY_MODE=$mode
  expect_fail "remove the default port"
  gen "reject explicit :80 ($mode)" TARGET=vps AUTH_URL=http://a.com:80 PROXY_MODE=$mode
  expect_fail "remove the default port"
  gen "reject leading-zero port ($mode)" TARGET=vps AUTH_URL=https://a.com:0443 PROXY_MODE=$mode
  expect_fail "must not start with 0"
  gen "reject out-of-range port ($mode)" TARGET=vps AUTH_URL=https://a.com:65536 PROXY_MODE=$mode
  expect_fail "1-65535"
  gen "reject non-numeric port ($mode)" TARGET=vps AUTH_URL=https://a.com:8x PROXY_MODE=$mode
  expect_fail "digits only"
done

gen "nginx mode rejects any port (http)" TARGET=vps AUTH_URL=http://a.com:8080 PROXY_MODE=nginx
expect_fail "serves on 80/443"
gen "nginx mode rejects any port (https)" TARGET=vps AUTH_URL=https://a.com:8443 PROXY_MODE=nginx
expect_fail "serves on 80/443"
gen "tls rejects an IP" TARGET=vps AUTH_URL=https://1.2.3.4 PROXY_MODE=nginx
expect_fail "IP addresses"
gen "vps requires AUTH_URL" TARGET=vps PROXY_MODE=nginx
expect_fail "AUTH_URL is required"

gen "bad PROXY_MODE" TARGET=vps AUTH_URL=https://a.com PROXY_MODE=bogus
expect_fail "unknown PROXY_MODE"
gen "bad LISTEN_IPV6" TARGET=vps AUTH_URL=https://a.com LISTEN_IPV6=yes
expect_fail "unknown LISTEN_IPV6"
gen "ACME_DIRECTORY must be https" TARGET=vps AUTH_URL=https://a.com ACME_DIRECTORY=http://x.org/dir
expect_fail "must be an https:// URL"
gen "ACME_DIRECTORY char allow-list" TARGET=vps AUTH_URL=https://a.com "ACME_DIRECTORY=https://x.org/dir; evil"
expect_fail "characters not allowed"
gen "bad TARGET" TARGET=bogus
expect_fail "unknown TARGET"

echo "entrypoint_test: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
