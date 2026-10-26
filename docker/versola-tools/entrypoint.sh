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

# "cert-sync" dispatch: keeps each edge-fronted client's `edgeClientCertificate` in central equal
# to the certificate its issuer (cert-manager, step-ca) last wrote -- see CertSyncTool.scala for
# the directory layout and the CENTRAL_URL/CENTRAL_SECRET environment it reads. Runs as a loop by
# default; CERT_SYNC_INTERVAL_SECONDS=0 makes it one pass, for a Job or a renewal hook. Same
# `migrate-lib` classpath as "migrate" above.
if [ "${1:-}" = "cert-sync" ]; then
  exec java -cp 'migrate-lib/*' versola.migrate.CertSyncTool
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
mkdir -p "$OUT_DIR"

case "$TARGET" in
  docker-local|vps) ;;
  *)
    echo "versola-tools: unknown TARGET '$TARGET' (expected docker-local or vps)" >&2
    exit 1
    ;;
esac

if [ "$TARGET" = "vps" ] && [ -z "$AUTH_URL" ]; then
  echo "versola-tools: AUTH_URL is required when TARGET=vps (e.g. https://auth.example.com)" >&2
  exit 1
fi

# POSTGRES_MODE (vps only, optional): `external` (the default) leaves Postgres to the deployment, as it
# always was; `bundled` adds a `postgres` service to the compose fragment, with the role, the database
# and the three schemas created on its first start. The container runs with network_mode: host and
# listens on 127.0.0.1:5432 only, so that is the one address POSTGRES_HOST can be (and what it defaults
# to) and the services' URLs get `sslmode=disable` from gen-env.scala's loopback rule. Its TLS is not
# a thing a CA file could be given for, so POSTGRES_CA_FILE is refused with it.
POSTGRES_MODE="${POSTGRES_MODE:-external}"
case "$POSTGRES_MODE" in
  external|bundled) ;;
  *)
    echo "versola-tools: unknown POSTGRES_MODE '$POSTGRES_MODE' (expected external or bundled)" >&2
    exit 1
    ;;
esac
if [ "$POSTGRES_MODE" = "bundled" ]; then
  if [ "$TARGET" != "vps" ]; then
    echo "versola-tools: POSTGRES_MODE=bundled is only supported when TARGET=vps" >&2
    exit 1
  fi
  POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1:5432}"
  if [ "$POSTGRES_HOST" != "127.0.0.1:5432" ]; then
    echo "versola-tools: POSTGRES_HOST must be 127.0.0.1:5432 (or unset) when POSTGRES_MODE=bundled; the container listens there only" >&2
    exit 1
  fi
  if [ -n "${POSTGRES_CA_FILE:-}" ]; then
    echo "versola-tools: POSTGRES_CA_FILE cannot be combined with POSTGRES_MODE=bundled (the container speaks no TLS)" >&2
    exit 1
  fi
  export POSTGRES_HOST
fi
if [ "$TARGET" = "vps" ] && [ -z "$POSTGRES_HOST" ]; then
  echo "versola-tools: POSTGRES_HOST is required when TARGET=vps (e.g. 127.0.0.1:5432)" >&2
  exit 1
fi

# MTLS_DOMAIN / CENTRAL_MTLS_DOMAIN (vps only, optional): the public DNS names of auth's and
# central's mutual-TLS listeners, e.g. id-mtls.example.com. They end up in the SAN of the listeners'
# certificates (the `mtls-init` service) and, for auth, in `mutual-tls.external-url` (gen-env.scala).
# Both are substituted into the compose fragment with sed below and `mtls-init` hands them to
# `step` as arguments, so what is accepted here is only what is certainly a DNS name: lowercase
# letters, digits, '.' and '-', labels of 1-63 characters that neither start nor end with '-',
# 253 characters in all. Anything else fails here, before it reaches a shell, sed or a certificate.
valid_dns_name() {
  name=$1
  case "$name" in
    ''|*[!a-z0-9.-]*|.*|*.|*..*) return 1 ;;
  esac
  [ "${#name}" -le 253 ] || return 1
  old_ifs=$IFS
  IFS=.
  # Safe to split unquoted: the name has no glob characters (checked above).
  set -- $name
  IFS=$old_ifs
  for label in "$@"; do
    [ "${#label}" -ge 1 ] && [ "${#label}" -le 63 ] || return 1
    case "$label" in
      -*|*-) return 1 ;;
    esac
  done
}
MTLS_DOMAIN="${MTLS_DOMAIN:-}"
CENTRAL_MTLS_DOMAIN="${CENTRAL_MTLS_DOMAIN:-}"
for var in MTLS_DOMAIN CENTRAL_MTLS_DOMAIN; do
  eval "value=\$$var"
  [ -n "$value" ] || continue
  if [ "$TARGET" != "vps" ]; then
    echo "versola-tools: $var is only supported when TARGET=vps" >&2
    exit 1
  fi
  if ! valid_dns_name "$value"; then
    echo "versola-tools: $var is not a valid DNS name (lowercase letters, digits, '.' and '-'; labels of 1-63 characters not starting or ending with '-'; at most 253 characters)" >&2
    exit 1
  fi
done
export MTLS_DOMAIN CENTRAL_MTLS_DOMAIN

# MTLS_CERT_DAYS (vps only, optional): how long the mutual-TLS leaves written by `mtls-init` live, in
# days; unset = 365, 0 = unlimited (100 years). Digits only, because it goes into the compose file with sed.
MTLS_CERT_DAYS="${MTLS_CERT_DAYS:-}"
if [ -n "$MTLS_CERT_DAYS" ]; then
  if [ "$TARGET" != "vps" ]; then
    echo "versola-tools: MTLS_CERT_DAYS is only supported when TARGET=vps" >&2
    exit 1
  fi
  case "$MTLS_CERT_DAYS" in
    *[!0-9]*) echo "versola-tools: MTLS_CERT_DAYS must be a number of days (0 = unlimited)" >&2; exit 1 ;;
  esac
  if [ "${#MTLS_CERT_DAYS}" -gt 5 ] || [ "$MTLS_CERT_DAYS" -gt 36500 ]; then
    echo "versola-tools: MTLS_CERT_DAYS is at most 36500 (100 years)" >&2
    exit 1
  fi
fi
export MTLS_CERT_DAYS

# IMAGE_REGISTRY (optional, both targets): the registry prefix of the images the compose fragment pulls
# (versola-central, versola-auth, versola-edge and this image, for `migrate` and `mtls-init`), for test
# builds pushed somewhere other than ghcr.io/versolauth -- versola-cli's `--registry`. The tag is still
# this image's own VERSION, so a build pushed to another registry has to be built with that VERSION.
# Goes into the compose file with sed (delimiter '|'), so only what is certainly a registry host and
# path is accepted: lowercase letters, digits, '.', '_', '-', '/' and, in the host, a ':port'.
IMAGE_REGISTRY="${IMAGE_REGISTRY:-ghcr.io/versolauth}"
case "$IMAGE_REGISTRY" in
  *[!a-z0-9._:/-]*|/*|*/|*//*|-*|.*|:*)
    echo "versola-tools: IMAGE_REGISTRY is not a registry host and path (e.g. ghcr.io/versolauth or registry.example.com:5000/versola: lowercase letters, digits, '.', '_', '-', '/', and a ':port' after the host)" >&2
    exit 1
    ;;
esac
registry_host=${IMAGE_REGISTRY%%/*}
case "${IMAGE_REGISTRY#"$registry_host"}" in
  *:*)
    echo "versola-tools: IMAGE_REGISTRY has a ':' outside the host (a tag does not go here: the tag is this image's VERSION)" >&2
    exit 1
    ;;
esac
export IMAGE_REGISTRY

# POSTGRES_DB (vps only; gen-env.scala validates the name and defaults to `auth`).
export POSTGRES_DB="${POSTGRES_DB:-}"
# POSTGRES_CA_FILE (vps only): a CA bundle versola-cli mounted into this container (its
# --postgres-ca). It becomes <bundle>/postgres-ca/ca.crt, which every service mounts read-only at
# /etc/versola/postgres-ca, and gen-env.scala is told (POSTGRES_SSL_ROOT_CERT) to write that path as
# `postgres.ssl-root-cert`. The directory exists on every vps bundle, empty when there is no CA: the
# compose fragment always mounts it, since a bind mount of a missing file would be created as a
# directory. Rebuilt on every run so a CA that was dropped does not linger.
unset POSTGRES_SSL_ROOT_CERT
if [ "$TARGET" = "vps" ]; then
  rm -rf "$OUT_DIR"/postgres-ca
  mkdir -p "$OUT_DIR"/postgres-ca
  chmod 755 "$OUT_DIR"/postgres-ca
fi
if [ -n "${POSTGRES_CA_FILE:-}" ]; then
  if [ "$TARGET" != "vps" ]; then
    echo "versola-tools: POSTGRES_CA_FILE is only supported when TARGET=vps" >&2
    exit 1
  fi
  if [ ! -f "$POSTGRES_CA_FILE" ]; then
    echo "versola-tools: POSTGRES_CA_FILE '$POSTGRES_CA_FILE' is not a file" >&2
    exit 1
  fi
  cp "$POSTGRES_CA_FILE" "$OUT_DIR"/postgres-ca/ca.crt
  chmod 644 "$OUT_DIR"/postgres-ca/ca.crt
  export POSTGRES_SSL_ROOT_CERT=/etc/versola/postgres-ca/ca.crt
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
#
# "$@": gen-env.scala's own flags (--central-mutual-tls-url=..., see its
# parseCliArgs), which versola-cli passes as this image's arguments.
printf '%s\n' "$TARGET" | java -cp 'lib/*' genEnv "$@"

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
#
# Copied under umask 077, in a subshell, so they're created 0600 from the
# start -- with the default 022 they'd sit world-readable on the host (this
# is a bind-mounted directory) until versola-cli's own chmod. Scoped to
# these three files on purpose: everything else written here is read by
# other containers running as their own users (OpenBao reads openbao.hcl,
# the proxy's nginx serves central-ui/), and 0600/0700 would lock them out.
# The rm first: cp onto an existing file (a reused OUT_DIR) keeps that
# file's old mode, umask or not.
(
  umask 077
  rm -f "$OUT_DIR"/auth.generated-secrets.env "$OUT_DIR"/central.generated-secrets.env "$OUT_DIR"/edge.generated-secrets.env
  cp .local/env/"$TARGET"/auth.generated-secrets.env    "$OUT_DIR"/auth.generated-secrets.env
  cp .local/env/"$TARGET"/central.generated-secrets.env "$OUT_DIR"/central.generated-secrets.env
  cp .local/env/"$TARGET"/edge.generated-secrets.env    "$OUT_DIR"/edge.generated-secrets.env
  # The private key of the `utils` client (see gen-env.scala's bootstrapUtilityClientLines):
  # not a generated-secrets file, so versola-cli never resolves or loads it, but it has to
  # leave this container or the key is gone with it.
  rm -f "$OUT_DIR"/utils.private-key.jwk
  cp .local/env/"$TARGET"/utils.private-key.jwk "$OUT_DIR"/utils.private-key.jwk
)

# What each of those secrets is, which services receive it and what to do
# when a deployment doesn't have it yet (see gen-env.scala's SecretSchema):
# versola-cli reads this to decide, before deploying a version, which
# secrets are missing from OpenBao. Names, shapes and policies only -- no
# values -- so, unlike the files above, it needs neither umask 077 nor
# anything else that keeps it private. The rm first for the same reason as
# above: a reused OUT_DIR must not keep a stale copy if this run fails to
# produce one (cp then fails under `set -e`, and nothing stale is left).
rm -f "$OUT_DIR"/secrets.schema.json
cp .local/env/"$TARGET"/secrets.schema.json "$OUT_DIR"/secrets.schema.json

# Bake this image's own version (and IMAGE_REGISTRY, validated above) into the compose fragment so
# it pulls the matching auth/central/edge/gateway images. sed instead of envsubst: fewer
# assumptions about what's installed in the base image, and there are no
# other "$" characters in the template to worry about mangling.
if [ "$TARGET" = "vps" ]; then
  COMPOSE_TEMPLATE=compose.fragment.vps.yml.template
else
  COMPOSE_TEMPLATE=compose.fragment.yml.template
fi
if [ "$TARGET" = "vps" ]; then
  # bundled: the lines the template marks `#@PG@` lose the marker, and the role and database the
  # container creates are the ones gen-env wrote into the configs (the three configs must agree: a
  # per-service --*-postgres-user would otherwise point a service at a role nobody created). external:
  # those lines are dropped, which leaves the file as it was before bundled existed.
  if [ "$POSTGRES_MODE" = "bundled" ]; then
    pg_role=
    for svc in auth central edge; do
      role=$(sed -n '/^postgres {/,/^}/s/^  user = "\([^"]*\)"$/\1/p' .local/env/vps/$svc.conf)
      if [ -z "$role" ] || { [ -n "$pg_role" ] && [ "$role" != "$pg_role" ]; }; then
        echo "versola-tools: POSTGRES_MODE=bundled needs one Postgres user for auth, central and edge (found '$pg_role' and '$role')" >&2
        exit 1
      fi
      pg_role=$role
    done
    case "$pg_role" in
      ''|[!a-z_]*|*[!a-z0-9_]*)
        echo "versola-tools: the Postgres user '$pg_role' is not a plain role name (lowercase letters, digits and '_') the container can create" >&2
        exit 1
        ;;
    esac
    pg_sed="s/^#@PG@//"
  else
    pg_role=
    pg_sed="/^#@PG@/d"
  fi
  # The validated public names (possibly empty) become `mtls-init`'s environment.
  sed -e "$pg_sed" -e "s/@POSTGRES_USER@/$pg_role/g" -e "s/@POSTGRES_DB@/${POSTGRES_DB:-auth}/g" -e "s/\${VERSION}/$VERSION/g" -e "s|\${REGISTRY}|$IMAGE_REGISTRY|g" -e "s/@MTLS_DOMAIN@/$MTLS_DOMAIN/g" -e "s/@CENTRAL_MTLS_DOMAIN@/$CENTRAL_MTLS_DOMAIN/g" -e "s/@MTLS_CERT_DAYS@/$MTLS_CERT_DAYS/g" \
    "$COMPOSE_TEMPLATE" > "$OUT_DIR"/compose.fragment.yml
else
  sed -e "s/\${VERSION}/$VERSION/g" -e "s|\${REGISTRY}|$IMAGE_REGISTRY|g" "$COMPOSE_TEMPLATE" > "$OUT_DIR"/compose.fragment.yml
fi

# Same split as the compose template just above, same reason: vps's
# listener binds 127.0.0.1 instead of 0.0.0.0, since network_mode: host
# (see compose.fragment.vps.yml.template) has no Docker port-publish step
# to restrict exposure on the other side the way docker-local's
# "127.0.0.1:8200:8200" does -- see openbao.hcl.vps.template's own comment.
if [ "$TARGET" = "vps" ]; then
  cp openbao.hcl.vps.template "$OUT_DIR"/openbao.hcl
  # What the compose fragment mounts into `step-ca` and `mtls-init` (they run in the step-ca
  # image, which has neither). Read by other users than ours, hence 644.
  cp mtls-init.sh step-ca-intermediates.sh "$OUT_DIR"/
  chmod 644 "$OUT_DIR"/mtls-init.sh "$OUT_DIR"/step-ca-intermediates.sh
  # The bundled Postgres' first-start script (mounted by the `postgres` service); absent otherwise, so an
  # `external` bundle holds exactly the files it did before, and a reused OUT_DIR keeps no stale copy.
  rm -f "$OUT_DIR"/postgres-init-schemas.sh
  if [ "$POSTGRES_MODE" = "bundled" ]; then
    cp postgres-init-schemas.sh "$OUT_DIR"/
    chmod 644 "$OUT_DIR"/postgres-init-schemas.sh
  fi
else
  cp openbao.hcl.template "$OUT_DIR"/openbao.hcl
fi

# Built admin console (central-ui), both targets: the reverse proxy
# versola-cli sets up serves it straight from the bundle as static files.
# Replaced wholesale rather than copied over, so a reused OUT_DIR can
# neither nest a second copy inside the first nor keep stale assets from a
# previous version.
rm -rf "$OUT_DIR"/central-ui
cp -R central-ui "$OUT_DIR"/central-ui

# nginx.conf/proxy_params.conf are docker-local only -- vps's nginx is a
# native install on the VPS, deployed by a separate pipeline (see the
# comment on compose.fragment.vps.yml.template), not something this image
# generates config for.
if [ "$TARGET" != "vps" ]; then
  cp nginx.conf.template "$OUT_DIR"/nginx.conf
  cp proxy_params.conf.template "$OUT_DIR"/proxy_params.conf
  echo "versola-tools: wrote auth.conf, central.conf, edge.conf, *.generated-secrets.env, secrets.schema.json, compose.fragment.yml, nginx.conf, proxy_params.conf, openbao.hcl, central-ui/ to $OUT_DIR"
else
  echo "versola-tools: wrote auth.conf, central.conf, edge.conf, *.generated-secrets.env, secrets.schema.json, compose.fragment.yml, openbao.hcl, mtls-init.sh, step-ca-intermediates.sh, central-ui/ to $OUT_DIR"
fi
