#!/usr/bin/env bash
# Makes the local stack (versola-cli's `~/.versola/active` bundle, served on :2821) reachable at a
# public https URL through ngrok, and points its issuer there, because the conformance suite runs in
# docker and FAPI 2.0 wants an https issuer.
#
#   ./expose-local-stack.sh up [fapi2|standard]
#                                    prints the issuer URL on its last line. The deployment serves one
#                                    discovery document for all its tenants (versola#553), so the
#                                    profile of the suite about to run picks it: `fapi2` (default) is
#                                    the generated PS256-only document; `standard` also lists RS256,
#                                    which OpenID Connect requires and the Basic and Config plans check.
#   ./expose-local-stack.sh down     puts the stack's config back and stops the tunnel
#
# `up` rewrites the bundle's auth.conf, central.conf and edge.conf in place (a copy of the originals is
# kept in ~/.versola/active-backup-localhost) and restarts central, auth and edge.
set -euo pipefail

LOCAL=http://localhost:2821
BUNDLE=$(ls -dt "$HOME"/.versola/active/bundle-*/ 2>/dev/null | head -1 || true)
BACKUP="$HOME/.versola/active-backup-localhost"
CONFS=(auth.conf central.conf edge.conf)

[[ -n "$BUNDLE" ]] || { echo "no bundle under ~/.versola/active: start the local stack first (develop.md)" >&2; exit 1; }

restart() {
  docker restart versola-central >/dev/null
  for _ in $(seq 1 40); do
    [[ $(curl -s -m 2 -o /dev/null localhost:8091/readiness -w '%{http_code}') == 200 ]] && break
    sleep 3
  done
  docker restart versola-auth versola-edge >/dev/null
}

case "${1:-}" in
  up)
    command -v ngrok >/dev/null || { echo "ngrok is not installed (brew install ngrok; ngrok config add-authtoken ...)" >&2; exit 1; }
    pgrep -f "ngrok http 2821" >/dev/null || nohup ngrok http 2821 --log=stdout >/tmp/versola-conformance-ngrok.log 2>&1 </dev/null &
    url=""
    for _ in $(seq 1 20); do
      url=$(curl -s localhost:4040/api/tunnels | python3 -c 'import json,sys; t=[x["public_url"] for x in json.load(sys.stdin)["tunnels"] if x["public_url"].startswith("https")]; print(t[0] if t else "")' 2>/dev/null || true)
      [[ -n "$url" ]] && break
      sleep 1
    done
    [[ -n "$url" ]] || { echo "ngrok gave no tunnel; see /tmp/versola-conformance-ngrok.log" >&2; exit 1; }
    host=${url#https://}
    if [[ ! -d "$BACKUP" ]]; then
      mkdir -p "$BACKUP"
      for f in "${CONFS[@]}"; do cp "$BUNDLE/$f" "$BACKUP/$f"; done
    fi
    profile="${2:-fapi2}"
    # Always rewrite from the originals, so running `up` twice (a new tunnel URL) does not stack edits.
    for f in "${CONFS[@]}"; do
      sed -e "s|$LOCAL|$url|g" -e "s|rp-id = \"localhost\"|rp-id = \"$host\"|" "$BACKUP/$f" >"$BUNDLE/$f"
    done
    if [[ "$profile" == standard ]]; then
      sed -i.bak -e 's|"id_token_signing_alg_values_supported": \["PS256"\]|"id_token_signing_alg_values_supported": ["RS256", "PS256"]|' \
                 -e 's|"request_object_signing_alg_values_supported": \["ES256", "PS256"\]|"request_object_signing_alg_values_supported": ["RS256", "ES256", "PS256"]|' "$BUNDLE/central.conf"
      rm -f "$BUNDLE/central.conf.bak"
    fi
    restart
    for _ in $(seq 1 40); do
      doc=$(curl -s -m 5 "$url/.well-known/openid-configuration" || true)
      [[ "$doc" == *"\"issuer\":\"$url\""* ]] && { echo "$url"; exit 0; }
      sleep 3
    done
    echo "the stack did not come back with issuer $url" >&2
    exit 1
    ;;
  down)
    [[ -d "$BACKUP" ]] || { echo "nothing to restore" >&2; exit 0; }
    for f in "${CONFS[@]}"; do cp "$BACKUP/$f" "$BUNDLE/$f"; done
    restart
    pkill -f "ngrok http 2821" || true
    rm -rf "$BACKUP"
    echo "restored $LOCAL"
    ;;
  *)
    echo "usage: $0 up|down" >&2
    exit 2
    ;;
esac
