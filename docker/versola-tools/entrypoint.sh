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
printf 'docker-local\n' | scala-cli run gen-env.scala

cp .local/env/docker-local/auth.conf    "$OUT_DIR"/auth.conf
cp .local/env/docker-local/central.conf "$OUT_DIR"/central.conf
cp .local/env/docker-local/edge.conf    "$OUT_DIR"/edge.conf

# Bake this image's own version into the compose fragment so it pulls the
# matching auth/central/edge/gateway images. sed instead of envsubst: fewer
# assumptions about what's installed in the base image, and there are no
# other "$" characters in the template to worry about mangling.
sed "s/\${VERSION}/$VERSION/g" compose.fragment.yml.template > "$OUT_DIR"/compose.fragment.yml
cp nginx.conf.template "$OUT_DIR"/nginx.conf
cp proxy_params.conf.template "$OUT_DIR"/proxy_params.conf

echo "versola-tools: wrote auth.conf, central.conf, edge.conf, compose.fragment.yml, nginx.conf, proxy_params.conf to $OUT_DIR"
