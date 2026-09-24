#!/bin/sh
set -eu

# "migrate" dispatch: backs the compose `migrate` service (see
# compose.fragment.yml.template / compose.fragment.vps.yml.template) and,
# through it, `versola migrate` (see versola-cli's internal/deploy/migrate.go).
# Bypasses the config-generation flow below entirely -- MigrateTool doesn't
# generate anything, it applies Flyway migrations against auth.conf/
# central.conf/edge.conf that "versola configure" already wrote and that
# compose mounts into this container read-only, the same way auth/central/
# edge's own services consume them. `migrate-lib`, not `lib` (used by
# genEnv below) -- see Dockerfile.tools' own comment on why the two jar
# sets are kept apart instead of merged onto one classpath.
if [ "${1:-}" = "migrate" ]; then
  # Shift the dispatch token itself off before exec'ing -- MigrateTool's own
  # `main` reads argv looking for `--dry-run`/`--service <name>` (see its own
  # doc comment); without this shift, those would always be one position off
  # (argv(0) == "migrate", not the first real flag), and MigrateTool would
  # never see them at all.
  shift
  exec java -cp 'migrate-lib/*' versola.migrate.MigrateTool "$@"
fi

OUT_DIR="${OUT_DIR:-/out}"
# TARGET picks which of gen-env.scala's non-interactive branches to run,
# and which compose fragment to emit. Defaults to docker-local so every
# existing caller (nothing sets TARGET yet -- see versola-cli's
# pullAndRunTools) keeps behaving exactly as before; "versola configure
# vps" is what will start passing TARGET=vps here (a later change, in
# versola-cli, not this image).
TARGET="${TARGET:-docker-local}"
# ENV_NAME is the literal environment name gen-env.scala writes into each
# service's config (its own `env` value) -- deliberately separate from
# TARGET above, which only picks network defaults. TARGET=vps is not
# itself an environment: the same VPS could run "prod" today and "qa"
# tomorrow (see gen-env.scala's own comment, and goshacodes' review on
# versolauth/versola#176). docker-local ignores this -- its env is always
# fixed to "docker-local" regardless. gen-env.scala reads it via
# sys.env, so it has to actually be in this process's environment, not
# just a shell-local variable -- hence the explicit export below, needed
# whenever this wasn't already set via `docker run -e ENV_NAME=...`.
ENV_NAME="${ENV_NAME:-prod}"
export ENV_NAME
# AUTH_URL, unlike ENV_NAME, has no sensible default: it's the public
# domain this deployment is actually reachable at, which is specific to
# whoever's deploying (see goshacodes' review on versolauth/versola#176:
# "this is our domain, users of cli will have other domains"). Required
# for vps, checked here (fails fast, before wasting time generating RSA
# key pairs) and again inside gen-env.scala itself (see its requiredEnv)
# for anyone invoking this image directly instead of through versola-cli.
export AUTH_URL="${AUTH_URL:-}"
# POSTGRES_HOST (host:port), same reasoning as AUTH_URL -- whether
# Postgres runs on this box or somewhere else entirely is specific to
# whoever's deploying (goshacodes' review on versolauth/versola#176:
# "user should provide this URL, we should not set defaults").
export POSTGRES_HOST="${POSTGRES_HOST:-}"
# PROXY_MODE (vps only): "nginx" -- the gateway owns the host's web ports
# itself; "external" -- another reverse proxy already does, and the
# gateway listens on 127.0.0.1:2821 behind it. Only changes listen.conf
# below. docker-local ignores it.
PROXY_MODE="${PROXY_MODE:-nginx}"
# ACME_DIRECTORY: which ACME server issues the vps certificate. Let's
# Encrypt production by default; point it at Let's Encrypt staging
# (https://acme-staging-v02.api.letsencrypt.org/directory) when testing, so
# repeated fresh deploys don't burn production rate limits.
ACME_DIRECTORY="${ACME_DIRECTORY:-https://acme-v02.api.letsencrypt.org/directory}"
# LISTEN_IPV6 (vps, PROXY_MODE=nginx): also listen on [::]. Off by default:
# on a host with IPv6 disabled in the kernel, a [::] listen makes nginx fail
# to start at all (and restart-loop). This image can't see the host's
# network stack, so versola-cli checks it and turns this on when it's there.
LISTEN_IPV6="${LISTEN_IPV6:-off}"
mkdir -p "$OUT_DIR"

case "$TARGET" in
  docker-local|vps) ;;
  *)
    echo "versola-tools: unknown TARGET '$TARGET' (expected docker-local or vps)" >&2
    exit 1
    ;;
esac

case "$PROXY_MODE" in
  nginx|external) ;;
  *)
    echo "versola-tools: unknown PROXY_MODE '$PROXY_MODE' (expected nginx or external)" >&2
    exit 1
    ;;
esac

case "$LISTEN_IPV6" in
  on|off) ;;
  *)
    echo "versola-tools: unknown LISTEN_IPV6 '$LISTEN_IPV6' (expected on or off)" >&2
    exit 1
    ;;
esac

if [ "$TARGET" = "vps" ] && [ -z "$AUTH_URL" ]; then
  echo "versola-tools: AUTH_URL is required when TARGET=vps (e.g. https://auth.example.com)" >&2
  exit 1
fi
if [ "$TARGET" = "vps" ] && [ -z "$POSTGRES_HOST" ]; then
  echo "versola-tools: POSTGRES_HOST is required when TARGET=vps (e.g. 127.0.0.1:5432)" >&2
  exit 1
fi

# vps serves Versola from the root of AUTH_URL's host: gen-env.scala
# builds every public URL as "$AUTH_URL/<endpoint>" (issuer, /authorize,
# /token, ...), while auth/edge and the gateway's routing are all mounted
# at "/" -- a path prefix would be advertised but never reachable, and the
# admin console's cookie paths (/central) assume the root too, so it can't
# be fixed by a prefix-stripping proxy in front either. A single trailing
# slash is just trimmed (common typo; left in, it would produce
# "https://host//authorize" and a passkey origin with a slash, which never
# matches a real origin). Anything else after the host is refused.
if [ "$TARGET" = "vps" ]; then
  AUTH_URL="${AUTH_URL%/}"
  case "${AUTH_URL#*://}" in
    */*)
      echo "versola-tools: AUTH_URL '$AUTH_URL' has a path -- Versola must be served from the root of its domain (e.g. https://auth.example.com)" >&2
      exit 1
      ;;
  esac
fi

# TLS is on only for vps owning the host's ports (PROXY_MODE=nginx) with an
# https:// AUTH_URL (scheme compared case-insensitively). Checked here, not
# where the nginx files are written, so a bad AUTH_URL fails before any
# config is generated instead of leaving a half-written bundle behind.
TLS=off
DOMAIN=""
if [ "$TARGET" = "vps" ] && [ "$PROXY_MODE" = "nginx" ]; then
  AUTH_SCHEME=$(printf '%s' "${AUTH_URL%%://*}" | tr 'A-Z' 'a-z')
  if [ "$AUTH_SCHEME" = "https" ] && [ "${AUTH_URL%%://*}" != "$AUTH_URL" ]; then
    TLS=on
    # Host part of AUTH_URL: drop scheme, then any path. Anything that
    # isn't a plain domain is refused rather than silently misconfigured:
    # a port (the certificate would be served on 443 while clients go
    # elsewhere), userinfo/query/fragment, an empty host, or an IP address
    # (Let's Encrypt doesn't issue for IPs by default -- the ACME module
    # would just keep failing, burning the failed-validation rate limit).
    DOMAIN="${AUTH_URL#*://}"
    DOMAIN=$(printf '%s' "${DOMAIN%%/*}" | tr 'A-Z' 'a-z')
    # Allow-list, not a deny-list: only letters, digits, dots and hyphens
    # can reach listen.conf -- a stray ';' or space would otherwise end up
    # inside the nginx config and restart-loop the gateway.
    DOMAIN_OK=yes
    case "$DOMAIN" in
      ""|*[!a-z0-9.-]*) DOMAIN_OK=no ;;
      *[!0-9.]*) ;;
      *) DOMAIN_OK=no ;;
    esac
    if [ "$DOMAIN_OK" = "no" ]; then
      echo "versola-tools: AUTH_URL '$AUTH_URL' -- with TLS on it must be https://<domain> (no port, IP address, user, query or fragment); Let's Encrypt issues the certificate for that domain on 443" >&2
      exit 1
    fi
  fi
fi

echo "versola-tools ${VERSION}: generating configs for $TARGET..."

# gen-env.scala's "Target" prompt is the only input it ever reads from
# stdin -- answering "$TARGET" is what makes it skip every other prompt
# and use that target's non-interactive defaults (docker-local's
# bridge-network ones, or vps's host-network ones -- see gen-env.scala).
# If a future version of the script changes that first prompt's behavior,
# this fails loudly (gen-env asks more questions on a closed stdin and
# gets empty answers, or hangs) instead of silently producing a wrong
# config, which is what the old fixed-stdin-answer-sequence approach this
# replaced would have done.
#
# `java -cp 'lib/*' genEnv` instead of `scala-cli run gen-env.scala` --
# this image no longer ships scala-cli, gen-env.scala is compiled ahead of
# time (see build.sbt's `tools` project) and staged here. Not
# ./bin/tools (the sbt-native-packager launcher also staged alongside
# lib/): that script's shebang is `#!/usr/bin/env bash`, and this image's
# base (Alpine) has no bash, only busybox's ash -- invoking java directly
# sidesteps needing it. `lib/*` is Java's own classpath wildcard syntax
# (expands to every jar in lib/, including gen-env.scala's compiled
# classes and the Scala runtime it needs) -- not a shell glob, so the
# quotes are required to stop the shell from expanding it first.
printf '%s\n' "$TARGET" | java -cp 'lib/*' genEnv

cp .local/env/"$TARGET"/auth.conf    "$OUT_DIR"/auth.conf
cp .local/env/"$TARGET"/central.conf "$OUT_DIR"/central.conf
cp .local/env/"$TARGET"/edge.conf    "$OUT_DIR"/edge.conf

# auth.conf/central.conf/edge.conf above reference these as ${VAR} HOCON
# placeholders instead of literal values (see gen-env.scala's secretField)
# -- versola-cli reads the freshly generated candidates here, resolves
# each against OpenBao (an existing value wins over regenerating one), and
# writes the result as <service>.secrets.env for Compose to load into the
# container. These *.generated-secrets.env files are the untrusted-until-
# resolved candidates, not the final values -- versola-cli, not this
# image, decides which of these actually get used.
cp .local/env/"$TARGET"/auth.generated-secrets.env    "$OUT_DIR"/auth.generated-secrets.env
cp .local/env/"$TARGET"/central.generated-secrets.env "$OUT_DIR"/central.generated-secrets.env
cp .local/env/"$TARGET"/edge.generated-secrets.env    "$OUT_DIR"/edge.generated-secrets.env

# Bake this image's own version into the compose fragment so it pulls the
# matching auth/central/edge/gateway images. sed instead of envsubst: fewer
# assumptions about what's installed in the base image, and there are no
# other "$" characters in the template to worry about mangling.
if [ "$TARGET" = "vps" ]; then
  COMPOSE_TEMPLATE=compose.fragment.vps.yml.template
else
  COMPOSE_TEMPLATE=compose.fragment.yml.template
fi
sed "s/\${VERSION}/$VERSION/g" "$COMPOSE_TEMPLATE" > "$OUT_DIR"/compose.fragment.yml

# Same split as the compose template just above, same reason: vps's
# listener binds 127.0.0.1 instead of 0.0.0.0, since network_mode: host
# (see compose.fragment.vps.yml.template) has no Docker port-publish step
# to restrict exposure on the other side the way docker-local's
# "127.0.0.1:8200:8200" does -- see openbao.hcl.vps.template's own comment.
if [ "$TARGET" = "vps" ]; then
  cp openbao.hcl.vps.template "$OUT_DIR"/openbao.hcl
else
  cp openbao.hcl.template "$OUT_DIR"/openbao.hcl
fi

# Gateway config, both targets: the routing (nginx.conf, proxy_params.conf)
# is shared; upstreams.conf and listen.conf are the only per-target parts
# (see nginx.conf.template's comments).
cp nginx.conf.template "$OUT_DIR"/nginx.conf
cp proxy_params.conf.template "$OUT_DIR"/proxy_params.conf
#
# TLS (vps, PROXY_MODE=nginx, https:// AUTH_URL only): nginx's own ACME
# module issues and renews the certificate for AUTH_URL's host -- no
# certbot, no separate issuance step. listen.conf turns the main server
# into the 443 one; acme.conf.template adds the issuer plus the port-80
# server the module answers HTTP-01 challenges on (everything else there
# redirects to https). It's a *template* on purpose: the gateway image's
# stock entrypoint envsubsts /etc/nginx/templates/*.template into conf.d/,
# filling ${NGINX_LOCAL_RESOLVERS} from the container's own resolv.conf
# (NGINX_ENTRYPOINT_LOCAL_RESOLVERS in the compose file) -- the module
# needs a `resolver` to reach the ACME server, and the right one is only
# known on the host, not here. Always written, comment-only when TLS is
# off, so the compose bind mount never points at a missing file.
# TLS / DOMAIN are decided (and AUTH_URL validated) up front, with the
# other input checks -- see the block after the POSTGRES_HOST check.

LISTEN6_80=""
LISTEN6_443=""
# Without IPv6 on the host, also keep the ACME client off AAAA records
# (Let's Encrypt has them) -- an IPv6 address picked there can't connect.
RESOLVER_OPTS=" ipv6=off"
if [ "$LISTEN_IPV6" = "on" ]; then
  LISTEN6_80="listen [::]:80;"
  LISTEN6_443="listen [::]:443 ssl;"
  RESOLVER_OPTS=""
fi

if [ "$TARGET" = "vps" ]; then
  cp upstreams.vps.conf.template "$OUT_DIR"/upstreams.conf
  if [ "$PROXY_MODE" = "external" ]; then
    # Real client IP from the reverse proxy in front of us: without this,
    # $remote_addr -- and so the X-Real-IP proxy_params.conf passes on,
    # which auth uses by default for per-IP throttling -- is 127.0.0.1 for
    # everyone. Only that proxy (127.0.0.1) is trusted, and only the last
    # X-Forwarded-For entry (the address it saw) is taken. That proxy MUST
    # set X-Forwarded-For itself (e.g. $proxy_add_x_forwarded_for):
    # otherwise a client-supplied header passes through untouched and its
    # value would be trusted here. Written for this mode only -- when the
    # gateway owns the host's ports, nothing legitimate is ever in front.
    # Loopback-only also means that proxy has to run on the host itself
    # (natively, or a container with network_mode: host) -- one in a
    # container on a Docker bridge network can't reach 127.0.0.1:2821.
    cat > "$OUT_DIR"/listen.conf <<EOF
listen 127.0.0.1:2821;
set_real_ip_from 127.0.0.1;
real_ip_header X-Forwarded-For;
EOF
  elif [ "$TLS" = "on" ]; then
    cat > "$OUT_DIR"/listen.conf <<EOF
listen 443 ssl;
$LISTEN6_443
http2 on;
acme_certificate letsencrypt $DOMAIN;
ssl_certificate \$acme_certificate;
ssl_certificate_key \$acme_certificate_key;
ssl_certificate_cache max=2;
EOF
  else
    printf 'listen 80;\n%s\n' "$LISTEN6_80" > "$OUT_DIR"/listen.conf
  fi
else
  cp upstreams.conf.template "$OUT_DIR"/upstreams.conf
  printf 'listen 2821;\n' > "$OUT_DIR"/listen.conf
fi

if [ "$TLS" = "on" ]; then
  cat > "$OUT_DIR"/acme.conf.template <<EOF
resolver \${NGINX_LOCAL_RESOLVERS}$RESOLVER_OPTS;

acme_issuer letsencrypt {
    uri $ACME_DIRECTORY;
    # An external docker volume (see compose.fragment.vps.yml.template):
    # without a persistent state_path every container restart would request a brand
    # new certificate and quickly run into Let's Encrypt's rate limits.
    state_path /var/cache/nginx/acme-letsencrypt;
    accept_terms_of_service;
}

server {
    # Required by the ACME module to answer HTTP-01 challenges.
    listen 80;
    $LISTEN6_80

    location / {
        return 301 https://\$host\$request_uri;
    }
}
EOF
else
  printf '# TLS is off for this deployment -- nothing to configure.\n' > "$OUT_DIR"/acme.conf.template
fi
echo "versola-tools: wrote auth.conf, central.conf, edge.conf, *.generated-secrets.env, compose.fragment.yml, nginx.conf, proxy_params.conf, upstreams.conf, listen.conf, acme.conf.template, openbao.hcl to $OUT_DIR (TLS: $TLS)"
