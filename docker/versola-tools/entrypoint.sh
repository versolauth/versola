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
  exec java -cp 'migrate-lib/*' versola.migrate.MigrateTool
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
if [ "$TARGET" = "vps" ] && [ -z "$POSTGRES_HOST" ]; then
  echo "versola-tools: POSTGRES_HOST is required when TARGET=vps (e.g. 127.0.0.1:5432)" >&2
  exit 1
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

# auth.conf/central.conf/edge.conf above reference these as ${?VAR} HOCON
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

# nginx.conf/proxy_params.conf are docker-local only -- vps's nginx is a
# native install on the VPS, deployed by a separate pipeline (see the
# comment on compose.fragment.vps.yml.template), not something this image
# generates config for.
if [ "$TARGET" != "vps" ]; then
  cp nginx.conf.template "$OUT_DIR"/nginx.conf
  cp proxy_params.conf.template "$OUT_DIR"/proxy_params.conf
  echo "versola-tools: wrote auth.conf, central.conf, edge.conf, *.generated-secrets.env, compose.fragment.yml, nginx.conf, proxy_params.conf, openbao.hcl to $OUT_DIR"
else
  echo "versola-tools: wrote auth.conf, central.conf, edge.conf, *.generated-secrets.env, compose.fragment.yml, openbao.hcl to $OUT_DIR"
fi
