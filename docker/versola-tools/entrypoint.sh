#!/bin/sh
set -eu

OUT_DIR="${OUT_DIR:-/out}"
mkdir -p "$OUT_DIR"

echo "versola-tools ${VERSION}: generating configs for docker-local..."

# gen-env.scala's "Name" prompt is the only input it ever reads from
# stdin -- answering "docker-local" is what makes it skip every other
# prompt and use the bridge-network defaults added in
# github.com/versolauth/versola (feat/gen-env-docker-local). If a future
# version of the script changes that first prompt's behavior, this fails
# loudly (gen-env asks more questions on a closed stdin and gets empty
# answers, or hangs) instead of silently producing a wrong config, which
# is what the old fixed-stdin-answer-sequence approach this replaced
# would have done.
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
printf 'docker-local\n' | java -cp 'lib/*' genEnv

cp .local/env/docker-local/auth.conf    "$OUT_DIR"/auth.conf
cp .local/env/docker-local/central.conf "$OUT_DIR"/central.conf
cp .local/env/docker-local/edge.conf    "$OUT_DIR"/edge.conf

# auth.conf/central.conf/edge.conf above reference these as ${?VAR} HOCON
# placeholders instead of literal values (see gen-env.scala's secretField)
# -- versola-cli reads the freshly generated candidates here, resolves
# each against OpenBao (an existing value wins over regenerating one), and
# writes the result as <service>.secrets.env for Compose to load into the
# container. These *.generated-secrets.env files are the untrusted-until-
# resolved candidates, not the final values -- versola-cli, not this
# image, decides which of these actually get used.
cp .local/env/docker-local/auth.generated-secrets.env    "$OUT_DIR"/auth.generated-secrets.env
cp .local/env/docker-local/central.generated-secrets.env "$OUT_DIR"/central.generated-secrets.env
cp .local/env/docker-local/edge.generated-secrets.env    "$OUT_DIR"/edge.generated-secrets.env

# Bake this image's own version into the compose fragment so it pulls the
# matching auth/central/edge/gateway images. sed instead of envsubst: fewer
# assumptions about what's installed in the base image, and there are no
# other "$" characters in the template to worry about mangling.
sed "s/\${VERSION}/$VERSION/g" compose.fragment.yml.template > "$OUT_DIR"/compose.fragment.yml
cp nginx.conf.template "$OUT_DIR"/nginx.conf
cp proxy_params.conf.template "$OUT_DIR"/proxy_params.conf
cp openbao.hcl.template "$OUT_DIR"/openbao.hcl

echo "versola-tools: wrote auth.conf, central.conf, edge.conf, *.generated-secrets.env, compose.fragment.yml, nginx.conf, proxy_params.conf, openbao.hcl to $OUT_DIR"
