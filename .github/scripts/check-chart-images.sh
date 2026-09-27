#!/usr/bin/env bash
#
# Asserts that every ghcr.io image a chart renders BY DEFAULT actually exists
# in the registry.
#
# Why this exists: k8s/*/values.yaml leaves `image.tag: ""` for every service,
# which _helpers.tpl resolves to .Chart.AppVersion. appVersion is hand-edited,
# nothing bumps it on release, and nothing checked it -- so k8s/loadgen shipped
# pointing at versola-loadgen:0.4.0 and versola-mockapi:0.4.0, tags that were
# never published (their docker-* jobs landed in c17190d8, after the 0.4.0
# release), and `helm install` with defaults gave ImagePullBackOff.
#
# This checks existence, NOT currency. If a chart's appVersion names an older
# release whose images are still in ghcr, this passes: the chart is stale but
# it installs. It fails only for a tag that was never published, which is the
# failure that actually breaks users. That also makes it cause-agnostic -- a
# typo'd `repository:`, or a new service whose docker-* job was forgotten,
# fails here the same way.
#
# Deliberately not run on `release`/`workflow_dispatch` events: there the
# docker-* jobs are pushing these very tags concurrently, so a check would
# race them. See the `helm-images` job in ci-cd.yml.
#
# Usage: .github/scripts/check-chart-images.sh [chart-dir ...]
#        defaults to k8s/loadgen and k8s/versola

set -euo pipefail

REGISTRY_HOST="ghcr.io"

# Both charts guard on a required `existingSecret` and refuse to render
# without one (the images run with -Denv.path and abort startup if no config
# volume is mounted). These names are never deployed -- they only get the
# templates past `required`, so that the image refs render.
render_chart() {
  local chart="$1"
  case "$(basename "$chart")" in
    loadgen)
      helm template check "$chart" \
        --set driver.config.existingSecret=ci-check \
        --set coordinator.config.existingSecret=ci-check
      ;;
    versola)
      helm template check "$chart" \
        --set secrets.existingSecret=ci-check
      ;;
    *)
      helm template check "$chart"
      ;;
  esac
}

# Anonymous pull token; all versolauth packages are public, so no credentials.
image_exists() {
  local repo="$1" tag="$2" token status
  token="$(curl -fsS "https://${REGISTRY_HOST}/token?scope=repository:${repo}:pull&service=${REGISTRY_HOST}" \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
  if [ -z "$token" ]; then
    echo "could not obtain a pull token for ${repo}" >&2
    return 2
  fi
  status="$(curl -sS -o /dev/null -w '%{http_code}' -I \
    -H "Authorization: Bearer ${token}" \
    -H 'Accept: application/vnd.oci.image.index.v1+json' \
    -H 'Accept: application/vnd.oci.image.manifest.v1+json' \
    -H 'Accept: application/vnd.docker.distribution.manifest.list.v2+json' \
    -H 'Accept: application/vnd.docker.distribution.manifest.v2+json' \
    "https://${REGISTRY_HOST}/v2/${repo}/manifests/${tag}")"
  [ "$status" = "200" ]
}

charts=("$@")
if [ ${#charts[@]} -eq 0 ]; then
  charts=(k8s/loadgen k8s/versola)
fi

failed=0
checked=0

for chart in "${charts[@]}"; do
  echo "==> ${chart} (appVersion $(helm show chart "$chart" | sed -n 's/^appVersion: *"\{0,1\}\([^"]*\)"\{0,1\}/\1/p'))"

  # Non-ghcr images (nginx:1.27-alpine and the like) are somebody else's
  # registry and not what this guard is about, so they are filtered out here.
  refs="$(render_chart "$chart" \
    | sed -n 's/^[[:space:]]*image:[[:space:]]*"\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p' \
    | grep "^${REGISTRY_HOST}/" \
    | sort -u)"

  if [ -z "$refs" ]; then
    echo "    no ${REGISTRY_HOST} images rendered -- check the chart or this script's parsing" >&2
    failed=1
    continue
  fi

  while IFS= read -r ref; do
    repo="${ref#"${REGISTRY_HOST}"/}"
    tag="${repo##*:}"
    repo="${repo%:*}"
    checked=$((checked + 1))
    if image_exists "$repo" "$tag"; then
      echo "    ok      ${ref}"
    else
      echo "    MISSING ${ref}" >&2
      failed=1
    fi
  done <<< "$refs"
done

echo
if [ "$failed" -ne 0 ]; then
  cat >&2 <<'MSG'
FAIL: a chart renders an image tag that does not exist in ghcr.

Bump the chart's appVersion in k8s/<chart>/Chart.yaml to a release whose
images were actually published, or publish the missing images. Images are
pushed only by the docker-* jobs in ci-cd.yml, and only on a `release`
event, tagged with the release name.
MSG
  exit 1
fi

echo "OK: all ${checked} ${REGISTRY_HOST} image refs exist."
