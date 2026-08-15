#!/bin/sh
set -eu

OUT_DIR="${OUT_DIR:-/out}"
# TARGET picks which of gen-env.scala's non-interactive branches to run,
# and which compose fragment to emit. Defaults to docker-local so every
# existing caller (nothing sets TARGET yet -- see versola-cli's
# pullAndRunTools) keeps behaving exactly as before; "versola configure
# vps" is what will start passing TARGET=vps here (a later change, in
# versola-cli, not this image).
TARGET="${TARGET:-docker-local}"
mkdir -p "$OUT_DIR"

case "$TARGET" in
  docker-local|vps) ;;
  *)
    echo "versola-tools: unknown TARGET '$TARGET' (expected docker-local or vps)" >&2
    exit 1
    ;;
esac

echo "versola-tools ${VERSION}: generating configs for $TARGET..."

# gen-env.scala's "Name" prompt is the only input it ever reads from
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
cp openbao.hcl.template "$OUT_DIR"/openbao.hcl

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
